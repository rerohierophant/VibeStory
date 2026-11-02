#!/usr/bin/env python3
"""Real HTTP + PostgreSQL acceptance, including SIGKILL and restart. No Python dependencies.

Uses DATABASE_URL/USER/PASSWORD and an already built bootJar. Creates its own accounts/project;
does not truncate existing data. Test processes and logs stay local, generated media is ignored.
"""
import argparse
import concurrent.futures
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


class Client:
    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token = None

    def request(self, method, path, body=None, expected=200, content_type=None):
        headers = {}
        data = None
        if body is not None:
            headers["Content-Type"] = content_type or "application/json"
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
        if method not in ("GET", "HEAD") and self.token:
            headers["X-CSRF-TOKEN"] = self.token
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read()
            assert response.status == expected, (method, path, response.status, payload.decode(errors="replace"))
            if "json" in response.headers.get("Content-Type", ""):
                return json.loads(payload)
            return payload

    def login(self, email, password):
        self.token = self.request("GET", "/api/auth/csrf")["token"]
        me = self.request("POST", "/api/auth/login", {"email": email, "password": password})
        self.token = self.request("GET", "/api/auth/csrf")["token"]
        return me


def until(check, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(0.15)
    raise AssertionError("Timed out waiting for acceptance condition")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-contracts", action="store_true", help="Capture the running springdoc OpenAPI")
    parser.add_argument("--phase2", action="store_true", help="Also verify project/style/episode/story persistence and cleanup")
    parser.add_argument("--phase3", action="store_true", help="Also verify breakdown, editable shots, confirmation and scoped asset names")
    parser.add_argument("--real-text", action="store_true", help="Use the configured real text adapter for one phase-three call")
    parser.add_argument("--phase4", action="store_true", help="Also verify asset versions and storyboard image tasks")
    parser.add_argument("--real-image", action="store_true", help="Exactly one configured real image request; all text remains Mock unless --real-text")
    args = parser.parse_args()
    if args.real_text:
        assert os.environ.get("TEXT_API_KEY"), "Load the local TEXT_API_KEY before --real-text"
    if args.real_image:
        assert os.environ.get("IMAGE_API_KEY"), "Load the local IMAGE_API_KEY before --real-image"
        assert os.environ.get("IMAGE_PROVIDER") == "minimax", "Configure MiniMax before --real-image"
    assert "DATABASE_URL" in os.environ, "Set DATABASE_URL to a PostgreSQL 16 JDBC URL"
    jdbc = os.environ["DATABASE_URL"]
    assert jdbc.startswith("jdbc:postgresql://"), "Expected jdbc:postgresql://..."
    connection = urllib.parse.urlparse(jdbc.removeprefix("jdbc:"))
    assert not connection.query, "Use a plain dedicated database URL for HTTP acceptance"
    psql = os.environ.get("PSQL") or shutil.which("psql")
    if not psql:
        for candidate in ("/opt/homebrew/opt/postgresql@16/bin/psql", "/usr/local/opt/postgresql@16/bin/psql"):
            if Path(candidate).is_file():
                psql = candidate
                break
    assert psql, "Install the PostgreSQL 16 client or set PSQL"
    db_env = {**os.environ, "DISPATCH_MODE": "database", "APP_ROLE": "all", "PGHOST": connection.hostname or "localhost", "PGPORT": str(connection.port or 5432),
              "PGDATABASE": connection.path.lstrip("/"), "PGUSER": os.environ.get("DATABASE_USER", "vibestory"),
              "PGPASSWORD": os.environ.get("DATABASE_PASSWORD", "")}

    def sql(statement):
        return subprocess.check_output([psql, "-X", "-Atq", "-v", "ON_ERROR_STOP=1", "-c", statement],
                                       env=db_env, text=True).strip()

    assert sql("show server_version_num").startswith("16"), "Use PostgreSQL 16"
    jar = ROOT / "apps/api/build/libs/vibestory-api.jar"
    assert jar.is_file(), "Run scripts/java21.sh bootJar first"
    jdk = os.environ.get("JAVA_HOME")
    if os.uname().sysname == "Darwin":
        jdk = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    java = str(Path(jdk) / "bin/java") if jdk else "java"
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    stamp = uuid.uuid4().hex[:12]
    email = f"phase1-{stamp}@example.test"
    password = secrets.token_urlsafe(24)
    runtime = ROOT / "data/verification" / stamp
    runtime.mkdir(parents=True)
    frozen_jar = runtime / "vibestory-api.jar"
    shutil.copy2(jar, frozen_jar)
    jar = frozen_jar
    process = None
    logs = []

    def stop(kill=False):
        nonlocal process
        if process and process.poll() is None:
            process.kill() if kill else process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        process = None

    def start(delay):
        nonlocal process
        env = {**os.environ, "DISPATCH_MODE": "database", "APP_ROLE": "all", "SEED_EMAIL": email, "SEED_PASSWORD": password,
               "STORAGE_ROOT": str(ROOT / "data/storage"), "WORKER_ENABLED": "true"}
        if not args.real_text and not args.real_image:
            env.update(MOCK_ONLY="true", TEXT_API_KEY="", IMAGE_API_KEY="", VIDEO_API_KEY="")
        log = (runtime / f"boot-{len(logs)}.log").open("wb")
        logs.append(log)
        process = subprocess.Popen([java, "-Djava.awt.headless=true", "-jar", str(jar),
            f"--server.port={port}", "--server.address=127.0.0.1", "--vibestory.worker.lease-duration=4s",
            "--vibestory.worker.heartbeat-interval=1s", f"--vibestory.mock.delay={delay}",
            "--vibestory.mock.failure-rate=0", "--vibestory.storage.cleanup-interval=500ms",
            "--vibestory.text.provider=" + ("openai-compatible" if args.real_text else "mock"),
            "--vibestory.image.provider=" + (os.environ["IMAGE_PROVIDER"] if args.real_image else "mock"),
            "--vibestory.video.provider=mock"],
            cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)

        def ready():
            assert process.poll() is None, f"API stopped; see {runtime}"
            try:
                Client(base).request("GET", "/v3/api-docs")
                # Spring runners may still be seeding immediately after Tomcat starts.
                if not sql(f"select count(*) from app_user where email = '{email}'") == "1":
                    return False
                return True
            except (OSError, AssertionError):
                return False

        until(ready, 90)
        client = Client(base)
        client.login(email, password)
        return client

    try:
        client = start("2s")
        me = client.request("GET", "/api/me")
        project = client.request("POST", "/api/projects", {"name": "Phase 1 HTTP acceptance", "visualType": "realistic", "style": ""}, 201)
        episode = client.request("POST", f"/api/projects/{project['id']}/episodes", {"title": "Acceptance"}, 201)
        fixture = subprocess.check_output([psql, "-X", "-Atq", "-v", f"user_id={int(me['id'])}",
            "-v", f"episode_id={int(episode['id'])}", "-f", str(ROOT / "scripts/fixtures/phase1-shot.sql")],
            env=db_env, text=True)
        fixture = json.loads(fixture.strip())
        shot = int(fixture["shotId"])
        nonce = str(uuid.uuid4())

        def submit(_):
            return client.request("POST", f"/api/shots/{shot}/video-tasks", {"regenerateNonce": nonce}, 202)["taskId"]

        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
            task_ids = list(pool.map(submit, range(10)))
        assert len(set(task_ids)) == 1, task_ids
        task_id = int(task_ids[0])
        view = until(lambda: (value if (value := client.request("GET", f"/api/tasks/{task_id}"))["ready"] else None))
        assert view["state"] == "idle" and view["attempts"] == 1 and view["basedOn"] == fixture["imageId"], view
        assert sql(f"select count(*) from task where target_id={shot}") == "1"
        assert sql(f"select count(*) from task_attempt where task_id={task_id}") == "1"
        video = client.request("GET", view["videoUrl"])
        assert b"ftyp" in video[:32] and len(video) > 1000
        print("PASS: HTTP login -> project -> episode -> 10 duplicate submissions -> one attempt -> ready MP4", flush=True)

        phase2 = None
        if args.phase2:
            from phase2_acceptance import PhaseTwoAcceptance
            phase2 = PhaseTwoAcceptance(client, sql, ROOT, int(me["id"]), until)
            phase2.prepare()

        phase3 = None
        if args.phase3 or args.real_text:
            from phase3_acceptance import PhaseThreeAcceptance
            phase3 = PhaseThreeAcceptance(client, sql, ROOT, int(me["id"]), until, runtime, args.real_text)
            phase3.prepare()

        phase4 = None
        if args.phase4 or args.real_image:
            from phase4_acceptance import PhaseFourAcceptance
            phase4 = PhaseFourAcceptance(client, ROOT, until, runtime, args.real_image)
            phase4.prepare()

        if args.write_contracts:
            spec = client.request("GET", "/v3/api-docs")
            # Server origins depend on the chosen test port; keep the shared contract portable.
            spec["servers"] = [{"url": "/", "description": "Current API origin"}]
            (ROOT / "packages/contracts/openapi.json").write_text(json.dumps(spec, ensure_ascii=False, indent=2, sort_keys=True) + "\n")

        metrics_before = client.request("GET", "/api/task-metrics")["groups"]
        assert metrics_before
        if not args.real_text and not args.real_image:
            assert all(group["provider"] == "mock" for group in metrics_before)
        stop()
        client = start("60s")
        metrics_after = client.request("GET", "/api/task-metrics")["groups"]
        assert sum(group["succeeded"] for group in metrics_after) >= sum(group["succeeded"] for group in metrics_before)
        print("PASS: Scoped task metrics survive JVM restart", flush=True)
        if phase2:
            phase2.check_restart(client)
        if phase3:
            phase3.check_restart(client)
        if phase4:
            phase4.check_restart(client)
        crash_id = int(client.request("POST", f"/api/shots/{shot}/video-tasks", {"regenerateNonce": str(uuid.uuid4())}, 202)["taskId"])
        until(lambda: client.request("GET", f"/api/tasks/{crash_id}")["state"] == "running")
        # Exceed the 4s lease: the same attempt must remain alive through periodic renewal.
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            running = client.request("GET", f"/api/tasks/{crash_id}")
            assert running["state"] == "running" and running["attempts"] == 1, running
            time.sleep(0.25)
        print("PASS: Worker renews its lease while the provider is blocked", flush=True)
        stop(kill=True)  # SIGKILL: no shutdown hook or in-memory completion.
        client = start("0s")
        if phase2:
            phase2.check_restart(client)
        if phase3:
            phase3.check_restart(client)
        if phase4:
            phase4.check_restart(client)
        recovered = until(lambda: (value if (value := client.request("GET", f"/api/tasks/{crash_id}"))["ready"] else None))
        assert recovered["attempts"] == 2, recovered
        assert [attempt["state"] for attempt in recovered["history"]] == ["abandoned", "succeeded"], recovered
        print("PASS: SIGKILL -> database lease expiry -> new process -> attempt 2 -> ready", flush=True)

        # A second seed-only account has no access to the first account's resources.
        other_email = f"phase1-other-{stamp}@example.test"
        # BCrypt hashes are copied only for fixture account creation, never exposed through an API.
        sql(f"insert into app_user(email, password_hash) select '{other_email}', password_hash from app_user where id={int(me['id'])}")
        other = Client(base)
        other.login(other_email, password)
        other.request("GET", f"/api/projects/{project['id']}", expected=404)
        other.request("GET", f"/api/projects/{project['id']}/episodes", expected=404)
        other.request("POST", f"/api/shots/{shot}/video-tasks", {"regenerateNonce": "cross-user"}, 404)
        other.request("GET", f"/api/tasks/{crash_id}", expected=404)
        other.request("GET", recovered["videoUrl"], expected=404)
        assert other.request("GET", "/api/task-metrics")["groups"] == []
        if phase2:
            phase2.check_scope_and_cleanup(client, other)
        if phase3:
            phase3.check_scope(client, other)
        if phase4:
            phase4.check_scope(client, other)
        client.request("POST", "/api/auth/logout", expected=204)
        client.request("GET", "/api/me", expected=401)
        print("PASS: Cross-user project/episode/shot/task/media access is 404; logout revokes the session", flush=True)
        print(f"Acceptance complete. Local logs: {runtime}", flush=True)
    finally:
        stop()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
