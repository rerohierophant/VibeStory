#!/usr/bin/env python3
"""ONE real MiniMax video submission, reusing the phase-four image. Never retries generation.

An exclusive persistent marker is written BEFORE submission. Running this script again refuses to spend
another call. A restart is attempted only after an external task ID is observed committed in PostgreSQL.
"""
import argparse
import importlib.util
import re
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('acceptance', ROOT / 'scripts/verify-phase1.py')
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)
MARKER = ROOT / 'data/verification/minimax-video-2026-11-02.once.json'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resume-local-rejection', action='store_true', help='Resume only a proven local preflight rejection with no external call')
    args = parser.parse_args()
    previous = json.loads(MARKER.read_text()) if MARKER.exists() else None
    if previous:
        assert args.resume_local_rejection and previous.get('errorCode') == 'VIDEO_CONFIGURATION_CHANGED' and not previous.get('localPreflightRecoveryUsed'), 'The one-video budget is reserved; no further paid submission is allowed'
    else:
        assert not args.resume_local_rejection, 'No prior local rejection exists'
    assert os.environ.get('IMAGE_API_KEY') or os.environ.get('VIDEO_API_KEY'), 'Load the existing local MiniMax key'
    source = ROOT / 'data/verification/097d5392e5e4/phase4-real-image.jpg'
    assert source.is_file(), 'The previously generated phase-four image is required; do not generate another'
    stamp = uuid.uuid4().hex[:10]
    runtime = ROOT / 'data/verification' / ('video-' + stamp)
    runtime.mkdir(parents=True)
    email, password = f'video-{stamp}@example.test', secrets.token_urlsafe(24)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    java = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip() + '/bin/java'
    database = urllib.parse.urlparse(os.environ['DATABASE_URL'].removeprefix('jdbc:'))
    env = {**os.environ, 'TEXT_PROVIDER': 'mock', 'IMAGE_PROVIDER': 'mock', 'VIDEO_PROVIDER': 'minimax',
           'VIDEO_MODEL': 'MiniMax-Hailuo-2.3-Fast', 'WORKER_ENABLED': 'true', 'SEED_EMAIL': email,
           'SEED_PASSWORD': password, 'STORAGE_ROOT': str(ROOT / 'data/storage')}
    db_env = {**env, 'PGHOST': database.hostname or 'localhost', 'PGPORT': str(database.port or 5432),
              'PGDATABASE': database.path.lstrip('/'), 'PGUSER': os.environ.get('DATABASE_USER', 'vibestory'), 'PGPASSWORD': os.environ.get('DATABASE_PASSWORD', '')}
    psql = os.environ.get('PSQL') or '/opt/homebrew/opt/postgresql@16/bin/psql'
    def sql(statement):
        return subprocess.check_output([psql, '-X', '-Atq', '-v', 'ON_ERROR_STOP=1', '-c', statement], env=db_env, text=True).strip()
    process = None
    logs = []
    summary = {**(previous or {}), 'realVideoSubmissions': 0, 'runtime': str(runtime), 'requestedModel': 'MiniMax-Hailuo-2.3-Fast'}
    owns_reservation = False
    def stop(kill=False):
        nonlocal process
        if process and process.poll() is None:
            process.kill() if kill else process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        process = None
    def start():
        nonlocal process
        log = (runtime / f'boot-{len(logs)}.log').open('wb'); logs.append(log)
        process = subprocess.Popen([java, '-Djava.awt.headless=true', '-jar', str(ROOT / 'apps/api/build/libs/vibestory-api.jar'),
            f'--server.port={port}', '--vibestory.worker.lease-duration=6s', '--vibestory.worker.heartbeat-interval=1s',
            '--vibestory.mock.delay=0ms'], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        def ready():
            assert process.poll() is None, 'API stopped; inspect the local runtime log'
            try:
                acceptance.Client(base).request('GET', '/v3/api-docs')
                return sql(f"select count(*) from app_user where email='{email}'") == '1'
            except OSError: return False
        acceptance.until(ready, 45)
        client = acceptance.Client(base); client.login(email, password); return client
    try:
        client = start()
        if previous:
            task_id = str(int(previous['taskId']))
            row = json.loads(sql(f"select json_build_object('userId',t.user_id,'email',u.email,'status',t.status,'code',t.error_code,'externalCount',(select count(*) from task_attempt where task_id=t.id and external_task_id is not null)) from task t join app_user u on u.id=t.user_id where t.id={int(task_id)}"))
            assert row['status'] == 'FAILED' and row['code'] == 'VIDEO_CONFIGURATION_CHANGED' and row['externalCount'] == 0, 'Cannot prove zero external submissions; do not retry'
            assert re.fullmatch(r'video-[0-9a-f]+@example.test', row['email']), 'Recovery is limited to this script synthetic fixture'
            seeded = client.request('GET', '/api/me')
            sql(f"update app_user set password_hash=(select password_hash from app_user where id={int(seeded['id'])}) where id={int(row['userId'])}")
            email = row['email']; env['SEED_EMAIL'] = email
            client = acceptance.Client(base); client.login(email, password)
            image = str(previous['sourceImageId'])
            summary['localPreflightRecoveryUsed'] = True
            summary['priorRuntime'] = previous['runtime']
            summary['localRejectedAttempts'] = 1
            owns_reservation = True
            MARKER.write_text(json.dumps(summary, indent=2) + '\n')
            sql(f"update task set retryable=true where id={int(task_id)} and error_code='VIDEO_CONFIGURATION_CHANGED' and status='FAILED'")
            client.request('POST', f'/api/tasks/{task_id}/retry', {'acknowledgeUnknown': False}, 202)
            expected_attempts = 2
        else:
            project = client.request('POST', '/api/projects', {'name': 'MiniMax one-video acceptance', 'visualType': 'realistic'}, 201)
            episode = client.request('POST', f"/api/projects/{project['id']}/episodes", {}, 201)
            client.request('PUT', f"/api/episodes/{episode['id']}/story", {'story': '雨夜的街道上，路灯照亮湿润地面。远处的便利店透出暖光，雨水在地面缓缓流动，镜头保持固定。'})
            breakdown = client.request('POST', f"/api/episodes/{episode['id']}/breakdowns", {}, 202)['taskId']
            acceptance.until(lambda: client.request('GET', f'/api/tasks/{breakdown}')['ready'])
            shot = client.request('GET', f"/api/episodes/{episode['id']}/shots")[0]
            client.request('PUT', f"/api/shots/{shot['id']}/status", {'status': 'confirmed'})
            me = client.request('GET', '/api/me')
            key = f"u/{int(me['id'])}/p/{int(project['id'])}/e/{int(episode['id'])}/shot/{int(shot['id'])}/phase4-first-frame.jpg"
            target = ROOT / 'data/storage' / key; target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, target)
            image = sql(f"insert into shot_image(shot_id,episode_id,version,selected,storage_key,media_type,label) values ({int(shot['id'])},{int(episode['id'])},1,true,'{key}','image/jpeg','复用第四阶段样图') returning id")
            summary.update(projectId=project['id'], episodeId=episode['id'], shotId=shot['id'], sourceImageId=image, reusedImage=str(source))
            # This exclusive marker is the hard limit, including ambiguous POST/network failures.
            with MARKER.open('x') as marker:
                json.dump({**summary, 'reserved': True}, marker)
            owns_reservation = True
            summary['businessSubmissions'] = 1
            task_id = client.request('POST', f"/api/shots/{shot['id']}/video-tasks", {}, 202)['taskId']
            summary['taskId'] = task_id
            MARKER.write_text(json.dumps(summary, indent=2) + '\n')
            expected_attempts = 1
        restarted = False
        deadline = time.monotonic() + 1200
        while time.monotonic() < deadline:
            task = client.request('GET', f'/api/tasks/{task_id}')
            if task['state'] in ('failed', 'unknown'):
                summary.update(state=task['state'], errorCode=task.get('errorCode'), attempts=task['attempts'])
                raise AssertionError('The single video attempt failed or became unknown; no retry will be made: ' + str(task.get('errorCode')))
            external = next((a.get('externalTaskId') for a in task['history'] if a.get('externalTaskId')), None)
            if external and not restarted and not task['ready']:
                summary['externalTaskId'] = external
                summary['state'] = 'running'
                MARKER.write_text(json.dumps(summary, indent=2) + '\n')
                stop(kill=True)
                client = start(); restarted = True
                print('External ID persisted; restarted API. Continuing queries only, no new video submission.', flush=True)
            if task['ready']:
                assert task['attempts'] == expected_attempts and task['basedOn'] == image
                clip = client.request('GET', task['videoUrl']); assert clip[4:8] == b'ftyp'
                output = runtime / 'phase5-real-video.mp4'; output.write_bytes(clip)
                summary['priorErrorCode'] = summary.pop('errorCode', None)
                summary.update(state='ready', attempts=task['attempts'], restartedWithExternalId=restarted, videoPath=str(output), bytes=len(clip))
                # Read-only Chrome playback validates the actual downloaded MP4 and duration.
                script = """
import { chromium, request } from '@playwright/test';
const base = process.env.VIDEO_TEST_BASE;
const api = await request.newContext({ baseURL: base });
let csrf = await (await api.get('/api/auth/csrf')).json();
const login = await api.post('/api/auth/login', { data: { email: process.env.SEED_EMAIL, password: process.env.SEED_PASSWORD }, headers: { [csrf.headerName]: csrf.token } });
if (!login.ok()) throw new Error('Playback login failed');
const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
const context = await browser.newContext({ storageState: await api.storageState() });
const page = await context.newPage(); await page.goto(base + '/api/me');
await page.setContent('<video controls src="' + base + process.env.VIDEO_TEST_PATH + '"></video>');
await page.waitForFunction(() => document.querySelector('video').readyState >= 1);
const metadata = await page.locator('video').evaluate(v => ({ duration: v.duration, width: v.videoWidth, height: v.videoHeight }));
console.log(JSON.stringify(metadata));
} finally { await browser.close(); await api.dispose(); }
"""
                metadata = subprocess.run(['node', '--input-type=module', '-e', script], cwd=ROOT / 'apps/web',
                    env={**env, 'VIDEO_TEST_BASE': base, 'VIDEO_TEST_PATH': task['videoUrl']}, capture_output=True, text=True, timeout=45)
                assert metadata.returncode == 0, 'Read-only playback verification failed; no generation retry'
                summary['actualVideo'] = json.loads(metadata.stdout.strip())
                print('PASS: ONE real MiniMax submission, fixed source, private MP4, Chrome playback. Local attempts: ' + str(task['attempts']), flush=True)
                break
            time.sleep(3)
        else:
            raise AssertionError('Video still pending; retain its task ID and do not submit another video')
    finally:
        summary['realVideoSubmissions'] = sum(p.read_text(errors='replace').count('提交 MiniMax 视频 task=') for p in runtime.glob('boot-*.log'))
        assert summary['realVideoSubmissions'] <= 1, 'One-video boundary violated'
        (runtime / 'phase5-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
        if owns_reservation: MARKER.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
        stop()
        for log in logs: log.close()
        print('One-video verification record:', runtime, flush=True)

if __name__ == '__main__':
    main()
