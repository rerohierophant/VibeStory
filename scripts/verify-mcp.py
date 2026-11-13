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
            verify(runtime, java_env, origin, email, other, password, episode["id"], api)
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
        assert len(tools) == 17
        assert {name for name, tool in tools.items() if tool["annotations"]["readOnlyHint"]} == {
            "list_projects", "get_episode", "list_shots", "get_shot_media", "get_task",
            "get_image_batch", "get_generation_batch", "list_generation_batches",
        }
        projects = mcp.call("list_projects", {"query": "雨巷"})
        assert projects["projects"][0]["episodes"][0]["id"] == episode_id
        assert "story" not in projects["projects"][0]["episodes"][0]
        episode = mcp.call("get_episode", target)
        story = "沈夜走进雨夜街道，抬头望向便利店。\n便利店的店员听见脚步声，起身开门。\n沈夜说：「先关门。外面有人在找我们。」"
        draft = {**target, "expected_story_revision": episode["storyRevision"], "story": story}
        saved = mcp.call("save_episode_story", draft)
        assert saved["story"] == story and saved["storyRevision"] > episode["storyRevision"]
        mcp.call("save_episode_story", {**draft, "story": "过期编辑"}, error=409)
        assert mcp.call("get_episode", target) == saved
        task_id = mcp.call("submit_breakdown", target)["taskId"]
        until(lambda: mcp.call("get_task", {"task_id": task_id})["ready"])
        page = mcp.call("list_shots", {**target, "limit": 2})
        assert page["total"] == 3 and page["next_offset"] == 2
        first = page["shots"][0]
        third = mcp.call("list_shots", {**target, "offset": 2})["shots"][0]
        mcp.call("submit_breakdown", target, error=409)
        preview = mcp.call("preview_shot_edit", {**target, "request_key": str(uuid.uuid4()),
                                                  "instruction": "把第 3 镜改成近景，台词短一点"})
        proposal = {**target, "proposal_id": preview["id"]}
        assert preview["state"] == "PENDING" and preview["changes"]
        assert mcp.call("list_shots", {**target, "offset": 2})["shots"][0] == third
        with client(runtime, java_env, origin, other, password, "outsider") as outsider:
            outsider.call("get_episode", target, error=404)
            outsider.call("save_episode_story", draft, error=404)
            outsider.call("list_shots", target, error=404)
            outsider.call("get_shot_media", {"shot_id": third["id"]}, error=404)
            outsider.call("get_task", {"task_id": task_id}, error=404)
            outsider.call("apply_shot_edit", proposal, error=404)
        applied = mcp.call("apply_shot_edit", proposal)
        assert applied["state"] == "APPLIED"
        assert mcp.call("apply_shot_edit", proposal) == applied
        assert api.request("GET", "/api/shots/" + third["id"])["shotSize"] == "近景"
        request = {**target, "request_key": str(uuid.uuid4()), "shot_ids": [first["id"], third["id"]]}
        batch_id = mcp.call("submit_image_batch", request)["id"]
        assert mcp.call("submit_image_batch", request)["id"] == batch_id
        batch_ref = {"batch_id": batch_id}
        until(lambda: mcp.call("get_image_batch", batch_ref)["progress"]["succeeded"] == 2)
        assert mcp.call("confirm_all_shots", target)["confirmed_count"] == 2
        assert mcp.call("get_generation_batch", batch_ref) == mcp.call("get_image_batch", batch_ref)
        video_id, frame = verify_video(mcp, api, target, first["id"], third["id"])
    with client(runtime, java_env, origin, email, password, "reopened") as mcp:
        latest = mcp.call("list_generation_batches", {**target, "type": "VIDEO"})["batches"][0]
        assert latest["id"] == video_id and latest["progress"]["succeeded"] == 1
        assert mcp.call("list_generation_batches", {**target, "type": "IMAGE"})["batches"][0]["id"] == batch_id
        assert mcp.call("get_shot_media", {"shot_id": third["id"]})["video"]["basedOn"] == frame
    with client(runtime, java_env, origin, other, password, "media-outsider") as mcp:
        shot = {"shot_id": third["id"], "image_id": frame}
        mcp.call("select_shot_image", shot, error=404)
        mcp.call("submit_video_batch", {**target, "request_key": str(uuid.uuid4()), "shots": [shot]}, error=404)
        mcp.call("get_generation_batch", {"batch_id": video_id}, error=404)
    print("PASS: 17 stdio tools, story version checks, breakdown and shot edit, scope checks, Mock image/video batches, and frozen first frame.", flush=True)


def verify_video(mcp, api, episode, first_shot, third_shot):
    api.request("POST", f"/api/episodes/{episode['episode_id']}/generation-batches/video",
                {"requestKey": str(uuid.uuid4())}, expected=400)
    shot = {"shot_id": third_shot}
    original = mcp.call("get_shot_media", shot)
    old_frame = original["selectedImageId"]
    candidate = original["candidates"]["images"][0]
    assert candidate["id"] == old_frame and "scene" in candidate["inputSnapshot"]
    assert candidate["url"] == f"/api/shot-images/{old_frame}/image"

    # The workbench creates a second candidate while the original remains selected.
    task = api.request("POST", f"/api/shots/{third_shot}/image-tasks", {"regenerateNonce": str(uuid.uuid4())}, expected=202)
    until(lambda: mcp.call("get_task", {"task_id": task["taskId"]})["ready"])
    media = mcp.call("get_shot_media", {**shot, "limit": 1})
    assert media["selectedImageId"] == old_frame and media["candidates"]["next_offset"] == 1
    page_two = mcp.call("get_shot_media", {**shot, "offset": 1})["candidates"]
    new_frame, = {media["candidates"]["images"][0]["id"], page_two["images"][0]["id"]} - {old_frame}
    mcp.call("select_shot_image", {"shot_id": first_shot, "image_id": new_frame}, error=404)
    assert mcp.call("select_shot_image", {**shot, "image_id": new_frame})["selectedImageId"] == new_frame
    assert api.request("GET", f"/api/shots/{third_shot}")["selectedImageId"] == new_frame

    first_frame = mcp.call("get_shot_media", {"shot_id": first_shot})["selectedImageId"]
    first = {"shot_id": first_shot, "image_id": first_frame}
    stale = {**shot, "image_id": old_frame}
    valid = {**shot, "image_id": new_frame}
    mixed = {**episode, "request_key": str(uuid.uuid4()), "shots": [first, stale]}
    partial = mcp.call("submit_video_batch", mixed)
    assert partial["progress"]["accepted"] == 1 and partial["progress"]["blocked"] == 1
    blocked = next(member for member in partial["members"] if member["shotId"] == third_shot)
    assert blocked["outcome"] == "blocked" and not blocked.get("taskId") and "首帧已变化" in blocked["reason"]
    assert mcp.call("submit_video_batch", {**mixed, "shots": [stale, first]})["id"] == partial["id"]
    mcp.call("submit_video_batch", {**mixed, "shots": [first, valid]}, error=409)

    request = {**episode, "request_key": str(uuid.uuid4()), "shots": [valid]}
    video = mcp.call("submit_video_batch", request)
    assert video["progress"]["accepted"] == 1
    api.request("PUT", f"/api/shots/{third_shot}/selected-image", {"imageId": int(old_frame)}, expected=204)
    assert mcp.call("submit_video_batch", request)["id"] == video["id"]
    ref = {"batch_id": video["id"]}
    until(lambda: mcp.call("get_generation_batch", ref)["progress"]["succeeded"] == 1)
    media = mcp.call("get_shot_media", shot)
    assert media["selectedImageId"] == old_frame and media["video"]["basedOn"] == new_frame
    assert mcp.call("get_task", {"task_id": video["members"][0]["taskId"]})["basedOn"] == new_frame
    return video["id"], new_frame


if __name__ == "__main__":
    main()
