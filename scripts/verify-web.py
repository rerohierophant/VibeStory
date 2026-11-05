#!/usr/bin/env python3
"""Real Chrome -> Vite -> Spring -> PostgreSQL acceptance, forcing all providers to Mock."""
import os
import shutil
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]

def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

def wait_url(url, process):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        assert process.poll() is None, 'Server exited; inspect ignored browser-verification logs'
        try:
            with urllib.request.urlopen(url, timeout=2):
                return
        except OSError:
            time.sleep(.2)
    raise RuntimeError('Server readiness timed out')

def main():
    api_port, web_port = port(), port()
    stamp = uuid.uuid4().hex[:10]
    runtime = ROOT / 'data/verification' / ('web-' + stamp)
    runtime.mkdir(parents=True)
    jar = runtime / 'vibestory-api.jar'
    shutil.copy2(ROOT / 'apps/api/build/libs/vibestory-api.jar', jar)
    email, other = f'web-{stamp}@example.test', f'web-other-{stamp}@example.test'
    password = secrets.token_urlsafe(24)
    java = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip() + '/bin/java'
    env = {**os.environ, "ADMISSION_ENABLED": "false", "DISPATCH_MODE": "database", "APP_ROLE": "all", 'TEXT_PROVIDER': 'mock', 'IMAGE_PROVIDER': 'mock', 'VIDEO_PROVIDER': 'mock', 'MOCK_ONLY': 'true',
           'TEXT_API_KEY': '', 'IMAGE_API_KEY': '', 'VIDEO_API_KEY': '',
           'WORKER_ENABLED': 'true', 'SEED_EMAIL': email, 'SEED_PASSWORD': password,
           'STORAGE_ROOT': str(runtime / 'storage'), 'API_PROXY_TARGET': f'http://127.0.0.1:{api_port}',
           'WEB_TEST_URL': f'http://127.0.0.1:{web_port}', 'WEB_TEST_EMAIL': email,
           'WEB_TEST_OTHER_EMAIL': other, 'WEB_TEST_PASSWORD': password, 'WEB_TEST_ARTIFACTS': str(runtime / 'browser')}
    processes = []
    try:
        with (runtime / 'api.log').open('wb') as log:
            api = subprocess.Popen([java, '-Djava.awt.headless=true', '-jar', str(jar),
                f'--server.port={api_port}', '--vibestory.mock-only=true', '--vibestory.text.provider=mock',
                '--vibestory.image.provider=mock', '--vibestory.video.provider=mock',
                '--vibestory.mock.delay=2s', '--vibestory.mock.failure-rate=0'], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(api)
            wait_url(f'http://127.0.0.1:{api_port}/v3/api-docs', api)
        # Create a second scoped account using the existing seed account's password hash.
        import urllib.parse
        database = urllib.parse.urlparse(os.environ['DATABASE_URL'].removeprefix('jdbc:'))
        db_env = {**env, 'PGHOST': database.hostname or 'localhost', 'PGPORT': str(database.port or 5432),
                  'PGDATABASE': database.path.lstrip('/'), 'PGUSER': os.environ.get('DATABASE_USER', 'vibestory'), 'PGPASSWORD': os.environ.get('DATABASE_PASSWORD', '')}
        psql = os.environ.get('PSQL') or '/opt/homebrew/opt/postgresql@16/bin/psql'
        for _ in range(30):
            created = subprocess.check_output([psql, '-X', '-Atq', '-v', 'ON_ERROR_STOP=1', '-c',
                f"insert into app_user(email,password_hash) select '{other}',password_hash from app_user where email='{email}' on conflict do nothing returning id"], env=db_env, text=True).strip()
            if created:
                break
            time.sleep(.1)
        assert created, 'Seed account did not become available'
        with (runtime / 'web.log').open('wb') as log:
            web = subprocess.Popen(['npm', 'run', 'dev', '--', '--host', '127.0.0.1', '--port', str(web_port), '--strictPort'], cwd=ROOT / 'apps/web', env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(web)
            wait_url(env['WEB_TEST_URL'], web)
        result = subprocess.run(['npx', 'playwright', 'test'], cwd=ROOT / 'apps/web', env=env)
        print('Browser verification artifacts:', runtime, flush=True)
        raise SystemExit(result.returncode)
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

if __name__ == '__main__':
    main()
