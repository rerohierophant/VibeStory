#!/usr/bin/env python3
"""Exercise the MCP process over stdio with a real local Spring/Mock backend and an isolated PostgreSQL database."""

from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import select
import shutil
import subprocess
from urllib.parse import urlparse
import uuid

from verify_phase7_support import Client, free_port, until, wait_url

ROOT = Path(__file__).resolve().parents[1]
LAUNCHER = ROOT / "apps/mcp/build/install/vibestory-mcp/bin/vibestory-mcp"


class McpClient:
    def __init__(self, process):
        self.process = process
        self.serial = 0

    def send(self, message):
        self.process.stdin.write(json.dumps({"jsonrpc": "2.0", **message}) + "\n")
        self.process.stdin.flush()

    def request(self, method, params):
        self.serial += 1
        self.send({"id": self.serial, "method": method, "params": params})
        assert select.select([self.process.stdout], [], [], 20)[0], f"MCP timeout: {method}"
        reply = json.loads(self.process.stdout.readline())
        assert reply.get("id") == self.serial and "result" in reply, reply
        return reply["result"]

    def initialize(self):
        info = self.request("initialize", {
            "protocolVersion": "2025-11-25",
            "clientInfo": {"name": "vibestory-verification", "version": "1"},
            "capabilities": {},
        })
        assert info["serverInfo"]["name"] == "vibestory"
        self.send({"method": "notifications/initialized"})

    def call(self, tool, params, error=None):
        response = self.request("tools/call", {"name": tool, "arguments": params})
        data = response["structuredContent"]
        assert response.get("isError", False) == (error is not None), (tool, data)
        if error is not None:
            assert data["status"] == error, (tool, data)
        return data


@contextmanager
def client(runtime, java_env, origin, email, password, name):
    # Run the installed launcher with fixture credentials, isolated from the local developer .env.
    env = {**java_env, "VIBESTORY_MCP_URL": origin, "VIBESTORY_MCP_EMAIL": email, "VIBESTORY_MCP_PASSWORD": password}
    with (runtime / f"mcp-{name}.log").open("w") as log:
        process = subprocess.Popen([str(LAUNCHER)], env=env, cwd=ROOT, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=log, text=True)
        try:
            mcp = McpClient(process)
            mcp.initialize()
            yield mcp
        finally:
            process.stdin.close()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            process.stdout.close()


def main():
    db_url = urlparse(os.environ["DATABASE_URL"].removeprefix("jdbc:"))
    assert db_url.scheme == "postgresql" and db_url.hostname in ("127.0.0.1", "localhost", "::1")
    assert db_url.path.startswith("/vibestory_mcp_"), "Use a disposable database named vibestory_mcp_*."
    runtime = ROOT / "data/verification" / ("mcp-" + uuid.uuid4().hex[:10])
    runtime.mkdir(parents=True)
    origin = f"http://127.0.0.1:{free_port()}"
    email = f"{runtime.name}@example.test"
    other = f"other-{email}"
    password = secrets.token_urlsafe(24)
    jdk = os.environ.get("JAVA_HOME")
    if os.uname().sysname == "Darwin":
        jdk = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    assert jdk, "Configure JAVA_HOME for Java 21"
    java_env = {**os.environ, "JAVA_HOME": jdk, "PATH": str(Path(jdk) / "bin") + ":" + os.environ["PATH"]}
    env = {**java_env, "ADMISSION_ENABLED": "false", "DISPATCH_MODE": "database", "APP_ROLE": "all",
           "TEXT_PROVIDER": "mock", "IMAGE_PROVIDER": "mock", "VIDEO_PROVIDER": "mock", "MOCK_ONLY": "true",
           "TEXT_API_KEY": "", "IMAGE_API_KEY": "", "VIDEO_API_KEY": "", "WORKER_ENABLED": "true",
           "SEED_EMAIL": email, "SEED_PASSWORD": password, "STORAGE_ROOT": str(runtime / "storage")}
    jar = ROOT / "apps/api/build/libs/vibestory-api.jar"
    psql = os.environ.get("PSQL") or shutil.which("psql") or "/opt/homebrew/opt/postgresql@16/bin/psql"
    pg_env = {**os.environ, "PGHOST": db_url.hostname, "PGPORT": str(db_url.port or 5432),
              "PGDATABASE": db_url.path.removeprefix("/"), "PGUSER": os.environ["DATABASE_USER"],
              "PGPASSWORD": os.environ.get("DATABASE_PASSWORD", "")}
    print("MCP verification artifacts:", runtime, flush=True)
    with (runtime / "api.log").open("wb") as log:
        process = subprocess.Popen([str(Path(jdk) / "bin/java"), "-Djava.awt.headless=true", "-jar", str(jar),
                                    "--server.address=127.0.0.1", f"--server.port={origin.rsplit(':', 1)[1]}",
                                    "--vibestory.mock.delay=250ms", "--vibestory.mock.failure-rate=0"],
                                   env=env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        try:
            wait_url(origin + "/v3/api-docs", process)
            def seed_other():
                sql = f"insert into app_user(email,password_hash) select '{other}',password_hash from app_user where email='{email}' returning id"
                return subprocess.check_output([psql, "-XAtq", "-v", "ON_ERROR_STOP=1", "-c", sql], env=pg_env, text=True).strip()
            until(seed_other)
            api = Client(origin)
            api.login(email, password)
            runtime_config = api.request("GET", "/api/runtime")
            assert {runtime_config[key]["provider"] for key in ("text", "image", "video")} == {"mock"}
            project = api.request("POST", "/api/projects", {"name": "MCP 雨巷", "visualType": "realistic"}, expected=201)
            episode = api.request("POST", f"/api/projects/{project['id']}/episodes", {}, expected=201)
            episode_id = episode["id"]
            api.request("PUT", f"/api/episodes/{episode_id}/story", {
                "story": "沈夜走进雨夜街道，抬头望向便利店。\n便利店的店员听见脚步声，起身开门。\n沈夜说：「先关门。外面有人在找我们。」"})
            verify(runtime, java_env, origin, email, other, password, episode_id, api)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def verify(runtime, java_env, origin, email, other, password, episode_id, api):
    target = {"episode_id": episode_id}
    with client(runtime, java_env, origin, email, password, "owner") as mcp:
        tools = {tool["name"]: tool for tool in mcp.request("tools/list", {})["tools"]}
        assert len(tools) == 10
        assert tools["list_shots"]["annotations"]["readOnlyHint"]
        assert not tools["apply_shot_edit"]["annotations"]["readOnlyHint"]
        projects = mcp.call("list_projects", {"query": "雨巷"})
        assert projects["projects"][0]["episodes"][0]["id"] == episode_id
        assert "story" not in projects["projects"][0]["episodes"][0]
        task_id = mcp.call("submit_breakdown", target)["taskId"]
        until(lambda: mcp.call("get_task", {"task_id": task_id})["ready"])
        page = mcp.call("list_shots", {**target, "limit": 2})
        assert page["total"] == 3 and page["next_offset"] == 2
        third = mcp.call("list_shots", {**target, "offset": 2})["shots"][0]
        mcp.call("submit_breakdown", target, error=409)
        preview = mcp.call("preview_shot_edit", {**target, "request_key": str(uuid.uuid4()),
                                                  "instruction": "把第 3 镜改成近景，台词短一点"})
        proposal = {**target, "proposal_id": preview["id"]}
        assert preview["state"] == "PENDING" and preview["changes"]
        assert mcp.call("list_shots", {**target, "offset": 2})["shots"][0] == third
        with client(runtime, java_env, origin, other, password, "outsider") as outsider:
            outsider.call("list_shots", target, error=404)
            outsider.call("get_task", {"task_id": task_id}, error=404)
            outsider.call("apply_shot_edit", proposal, error=404)
        applied = mcp.call("apply_shot_edit", proposal)
        assert applied["state"] == "APPLIED"
        assert mcp.call("apply_shot_edit", proposal) == applied
        assert api.request("GET", "/api/shots/" + third["id"])["shotSize"] == "近景"
        assert mcp.call("confirm_all_shots", target)["confirmed_count"] == 3
        request = {**target, "request_key": str(uuid.uuid4()), "shot_ids": [third["id"]]}
        batch_id = mcp.call("submit_image_batch", request)["id"]
        assert mcp.call("submit_image_batch", request)["id"] == batch_id
        until(lambda: mcp.call("get_image_batch", {"batch_id": batch_id})["progress"]["succeeded"] == 1)
        assert mcp.call("list_shots", {**target, "offset": 2})["shots"][0]["selectedImageId"]
    print("PASS: stdio discovery, project lookup, breakdown, edit preview/apply, cross-account isolation, and Mock image batch.", flush=True)


if __name__ == "__main__":
    main()
