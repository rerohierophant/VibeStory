#!/usr/bin/env python3
"""Two JVM Workers, PostgreSQL, real RabbitMQ and an OWNED Redis process; all providers Mock."""
from base64 import b64encode
from datetime import datetime
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
from verify_phase7_support import Client, until, free_port, wait_url

ROOT = Path(__file__).resolve().parents[1]


def main():
    stamp = uuid.uuid4().hex[:10]
    runtime = ROOT / 'data/verification' / ('phase8-' + stamp)
    runtime.mkdir(parents=True)
    jar = runtime / 'vibestory-api.jar'
    shutil.copy2(ROOT / 'apps/api/build/libs/vibestory-api.jar', jar)
    broker_port, management = int(os.environ['TEST_RABBITMQ_PORT']), int(os.environ['TEST_RABBITMQ_MANAGEMENT_PORT'])
    assert broker_port != 5672 and management != 15672
    redis_port, api_port, web_port = free_port(), free_port(), free_port()
    namespace = 'vibestory-rate-' + stamp
    email, password = f'rate-{stamp}@example.test', secrets.token_urlsafe(24)
    java = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip() + '/bin/java'
    env = {**os.environ, 'TEXT_PROVIDER': 'mock', 'IMAGE_PROVIDER': 'mock', 'VIDEO_PROVIDER': 'mock', 'MOCK_ONLY': 'true',
           'TEXT_API_KEY': '', 'IMAGE_API_KEY': '', 'VIDEO_API_KEY': '', 'DISPATCH_MODE': 'rabbit', 'ADMISSION_ENABLED': 'true',
           'RABBITMQ_PORT': str(broker_port), 'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': str(redis_port), 'REDIS_USER': '', 'REDIS_PASSWORD': '',
           'MQ_NAMESPACE': namespace, 'ADMISSION_NAMESPACE': namespace, 'MEDIA_CREDENTIAL_ID': 'shared-media', 'TEXT_CREDENTIAL_ID': 'shared-text',
           'MEDIA_SUBMIT_RATE': '2', 'MEDIA_SUBMIT_BURST': '2', 'TEXT_SUBMIT_RATE': '2', 'TEXT_SUBMIT_BURST': '2',
           'ADMISSION_PERMIT_TTL': '2s', 'ADMISSION_ERROR_BACKOFF': '500ms', 'WORKER_ENABLED': 'true',
           'SEED_EMAIL': '', 'SEED_PASSWORD': '', 'STORAGE_ROOT': str(runtime / 'storage')}
    database = urllib.parse.urlparse(env['DATABASE_URL'].removeprefix('jdbc:'))
    assert not database.query
    schema = 'vibestory_rate_' + stamp
    db_env = {**env, 'PGHOST': database.hostname or 'localhost', 'PGPORT': str(database.port or 5432),
              'PGDATABASE': database.path.lstrip('/'), 'PGUSER': env.get('DATABASE_USER', 'vibestory'), 'PGPASSWORD': env.get('DATABASE_PASSWORD', ''),
              'PGOPTIONS': '-c search_path=' + schema}
    env['DATABASE_URL'] += '?currentSchema=' + schema
    env['SPRING_FLYWAY_SCHEMAS'] = schema
    psql = shutil.which('psql') or '/opt/homebrew/opt/postgresql@16/bin/psql'
    cli = shutil.which('redis-cli')
    assert cli and shutil.which('redis-server'), 'Install Redis locally; no shared Redis is stopped'
    processes, logs = [], []
    summary = {'providers': ['mock'] * 3, 'realGenerationEnabled': False, 'namespace': namespace, 'checks': []}
    redis_process = None

    def sql(query):
        return subprocess.check_output([psql, '-X', '-Atq', '-v', 'ON_ERROR_STOP=1', '-c', query], env=db_env, text=True).strip()

    def redis(*args):
        return subprocess.check_output([cli, '-h', '127.0.0.1', '-p', str(redis_port), '--raw', *args], text=True, stderr=subprocess.DEVNULL).strip()

    def start_redis():
        log = (runtime / ('redis-' + str(len(logs)) + '.log')).open('wb'); logs.append(log)
        process = subprocess.Popen(['redis-server', '--bind', '127.0.0.1', '--protected-mode', 'yes', '--port', str(redis_port),
                                   '--dir', str(runtime), '--save', '', '--appendonly', 'no', '--maxmemory-policy', 'noeviction'], stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        def ping():
            try: return redis('PING') == 'PONG'
            except subprocess.CalledProcessError: return False
        until(ping)
        return process

    def broker(method, path, body=None):
        auth = b64encode((env.get('RABBITMQ_USER', 'guest') + ':' + env.get('RABBITMQ_PASSWORD', 'guest')).encode()).decode()
        req = urllib.request.Request(f'http://127.0.0.1:{management}/api/' + path, method=method,
            data=None if body is None else json.dumps(body).encode(), headers={'Authorization': 'Basic ' + auth, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=10) as response:
            payload = response.read()
            return json.loads(payload) if payload else None

    def boot(role, name):
        port = api_port if role == 'api' else free_port()
        log = (runtime / (name + '.log')).open('wb'); logs.append(log)
        process = subprocess.Popen([java, '-Djava.awt.headless=true', '-jar', str(jar), f'--server.port={port}',
            '--vibestory.worker.lease-duration=6s', '--vibestory.worker.heartbeat-interval=1s', '--vibestory.dispatch.prefetch=1',
            '--vibestory.dispatch.text-slots=1', '--vibestory.dispatch.image-slots=1', '--vibestory.dispatch.video-slots=1',
            '--vibestory.mock.delay=3s', '--vibestory.mock.failure-rate=0', '--vibestory.mock.submission-probe=' + str(runtime / (name + '-probe.jsonl')),
            '--vibestory.seed.email=' + (email if role == 'api' else ''), '--vibestory.seed.password=' + (password if role == 'api' else '')],
            cwd=ROOT, env={**env, 'APP_ROLE': role}, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process); wait_url(f'http://127.0.0.1:{port}/v3/api-docs', process)
        return process

    def stop(process, kill=False):
        if process is not None and process.poll() is None:
            process.kill() if kill else process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()

    def passed(name):
        summary['checks'].append(name); print('PASS:', name, flush=True)

    def complete(ids):
        for task in ids:
            view = until(lambda: v if (v := client.request('GET', '/api/tasks/' + str(task)))['ready'] else None, timeout=90)
            assert view['attempts'] == 1, view

    def submit(eid, ids, kind):
        view = client.request('POST', f'/api/episodes/{eid}/generation-batches', {'type': kind, 'requestKey': uuid.uuid4().hex, 'shotIds': [str(x) for x in ids]}, 202)
        assert view['progress']['accepted'] == len(ids), view
        return [int(m['taskId']) for m in view['members']]

    def probes():
        return [json.loads(line) for file in runtime.glob('*-probe.jsonl') for line in file.read_text().splitlines()]

    def audit():
        return json.loads(sql("select coalesce(json_agg(row_to_json(x)),'[]') from (select a.*,t.type,t.user_id from generation_admission a join task t on t.id=a.task_id order by a.created_at) x"))

    def assert_curve(rows):
        groups = {}
        for row in rows:
            if row['outcome'] == 'ALLOWED': groups.setdefault((row['bucket_id'], row['epoch']), []).append(row['redis_at_ms'])
        for values in groups.values():
            values.sort()
            for a in range(len(values)):
                for b in range(a, len(values)):
                    assert b - a + 1 <= 2 + 2 * (values[b] - values[a]) / 1000 + .001, 'shared token bucket bound exceeded'

    try:
        sql('create schema ' + schema)
        redis_process = start_redis()
        summary['redisVersion'] = next(x.split(':')[1] for x in redis('INFO', 'server').splitlines() if x.startswith('redis_version:'))
        summary['rabbitmqVersion'] = broker('GET', 'overview')['rabbitmq_version']
        api = boot('api', 'api')
        client = Client(f'http://127.0.0.1:{api_port}');client.login(email, password)
        user = int(client.request('GET', '/api/me')['id'])
        project = client.request('POST', '/api/projects', {'name': 'Redis ' + stamp, 'visualType': 'realistic', 'style': ''}, 201)
        episode = client.request('POST', f"/api/projects/{project['id']}/episodes", {'title': '共享限流'}, 201)
        pid, eid = int(project['id']), int(episode['id'])
        ids = json.loads(sql(f"with s as (insert into shot(user_id,project_id,episode_id,seq,status,description) select {user},{pid},{eid},n,'CONFIRMED','Mock rate '||n from generate_series(1,28) n returning id) select json_agg(id order by id) from s"))
        sql(f"insert into shot_image(shot_id,episode_id,selected,storage_key) select id,episode_id,true,'mock-first-frame' from shot where episode_id={eid}")
        tasks = submit(eid, ids[:8], 'IMAGE') + submit(eid, ids[8:16], 'VIDEO')
        assert sql('select count(*) from task_attempt') == '0'
        notification = json.loads(sql(f"select json_build_object('schemaVersion',1,'eventId',o.id,'taskId',t.id,'dispatchGeneration',t.dispatch_generation,'type',t.type,'userId',t.user_id,'projectId',t.project_id,'episodeId',t.episode_id) from task t join task_outbox o on o.task_id=t.id and o.dispatch_generation=t.dispatch_generation where t.id={tasks[0]}"))
        for _ in range(2):
            assert broker('POST', f'exchanges/%2F/{namespace}.mock.tasks/publish', {'properties': {'delivery_mode': 2, 'content_type': 'application/json'}, 'routing_key': 'image', 'payload': json.dumps(notification), 'payload_encoding': 'string'})['routed']
        w1, w2 = boot('worker', 'worker-1'), boot('worker', 'worker-2')
        until(lambda: int(sql("select count(*) from task where status='QUEUED' and admission_wait_reason is not null and attempt_count=0")) > 0)
        passed('API accepts batches; shared admission waits stay QUEUED with zero attempts')
        complete(tasks)
        rows, entries = audit(), probes()
        assert_curve(rows)
        assert len({r['bucket_id'] for r in rows}) == 1, 'MiniMax image/video must share one bucket'
        assert len({r['owner'].split(':')[0] for r in entries}) == 2, 'Both independent JVM Workers must execute'
        assert sorted(r['taskId'] for r in entries) == sorted(tasks), 'Independent Mock adapter probe must see exactly one call per task'
        by_id = {r['id']: r for r in rows}
        delays = [(datetime.fromisoformat(r['enteredAt'].replace('Z', '+00:00')).timestamp() * 1000 - by_id[r['permitId']]['redis_at_ms']) for r in entries]
        assert all(-10 <= delay <= 2000 for delay in delays), delays
        summary['maxAdmissionToMockEntryMs'] = max(delays)
        passed('Two JVMs share image/video rate/burst; independent adapter probes match task identity, call count and short permit age')
        # Calls that already crossed the boundary must finish while new submissions fail closed.
        running = submit(eid, ids[16:18], 'VIDEO')
        until(lambda: int(sql("select count(*) from task where status='RUNNING'")) > 0)
        stop(redis_process, kill=True)
        queued = submit(eid, ids[18:24], 'IMAGE')
        until(lambda: int(sql("select count(*) from task where admission_wait_reason='REDIS_UNAVAILABLE' and attempt_count=0")) >= 1)
        complete(running)
        assert all(client.request('GET', f'/api/tasks/{task}')['attempts'] == 0 for task in queued)
        stop(w2); w2 = boot('worker', 'worker-restarted')
        assert all(client.request('GET', f'/api/tasks/{task}')['attempts'] == 0 for task in queued)
        passed('Redis process outage fails closed; issued calls finish, new tasks have zero attempts, Worker restart cannot bypass admission')
        old_epoch = int(sql('select epoch from generation_bucket'))
        redis_process = start_redis()
        until(lambda: int(sql('select epoch from generation_bucket')) == old_epoch + 1)
        complete(queued)
        assert int(sql('select epoch from generation_bucket')) == old_epoch + 1
        epoch_row = json.loads(sql("select row_to_json(b) from generation_bucket b"))
        first = int(sql(f"select min(redis_at_ms) from generation_admission where outcome='ALLOWED' and epoch={old_epoch+1}"))
        activation = datetime.fromisoformat(epoch_row['updated_at']).timestamp() * 1000
        assert first - activation >= 400, 'Restart must rebuild empty and refill; no fresh burst'
        passed('Redis restart advances one durable epoch and refills from zero across both Workers')
        # Deliberate loss of one bucket (not a global FLUSH) follows the same coordinated protocol.
        tokens = redis('KEYS', namespace + ':admission:*:tokens').splitlines()
        assert len(tokens) == 1
        redis('DEL', tokens[0])
        state_loss = submit(eid, ids[24:28], 'VIDEO')
        complete(state_loss)
        assert int(sql('select epoch from generation_bucket')) == old_epoch + 2
        passed('Individual bucket loss is fenced and rebuilt once, without giving each Worker a full burst')
        rows, entries = audit(), probes();assert_curve(rows)
        assert len(entries) == len(tasks + running + queued + state_loss)
        assert len({r['taskId'] for r in entries}) == len(entries)
        assert len([r for r in rows if r['submitted_at']]) == len(entries)
        # Verify page submission and persisted progress with Redis enabled as well.
        browser_env = {**env, 'WEB_TEST_URL': f'http://127.0.0.1:{web_port}', 'API_PROXY_TARGET': f'http://127.0.0.1:{api_port}',
                       'WEB_TEST_EMAIL': email, 'WEB_TEST_PASSWORD': password, 'WEB_TEST_ARTIFACTS': str(runtime / 'browser')}
        log = (runtime / 'web.log').open('wb');logs.append(log)
        web = subprocess.Popen(['npm', 'run', 'dev', '--', '--host', '127.0.0.1', '--port', str(web_port), '--strictPort'], cwd=ROOT / 'apps/web', env=browser_env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(web);wait_url(browser_env['WEB_TEST_URL'], web)
        subprocess.run(['npx', 'playwright', 'test', 'batches.spec.ts'], cwd=ROOT / 'apps/web', env=browser_env, check=True)
        passed('Chrome batch image/video page and refresh recovery work through RabbitMQ plus Redis')
        rows, entries = audit(), probes();assert_curve(rows)
        by_id = {r['id']: r for r in rows}
        all_delays = []
        for entry in entries:
            granted = by_id[entry['permitId']]
            assert granted['submitted_at'] and not granted['expired']
            assert entry['taskId'] == granted['task_id'] and entry['attemptId'] == granted['attempt_id']
            all_delays.append(datetime.fromisoformat(entry['enteredAt'].replace('Z', '+00:00')).timestamp() * 1000 - granted['redis_at_ms'])
        assert all(-10 <= d <= 2000 for d in all_delays)
        assert len({e['taskId'] for e in entries}) == len(entries) == len([r for r in rows if r['submitted_at']])
        summary['maxAdmissionToMockEntryMs'] = max(all_delays)
        summary['mockSubmissions'] = len(entries)
        summary['metrics'] = client.request('GET', '/api/admission-metrics')
        summary['admissions'] = len(rows);summary['mockSubmissionsBeforeBrowser'] = len(tasks + running + queued + state_loss)
        (runtime / 'admission-audit.json').write_text(json.dumps(rows, indent=2) + '\n')
        keys = redis('KEYS', namespace + ':admission:*').splitlines()
        assert all(len(key.removeprefix(namespace + ':admission:').split(':')[0]) == 64 for key in keys)
        summary['status'] = 'passed'
    except Exception:
        summary['status'] = 'failed'
        raise
    finally:
        for process in reversed(processes): stop(process)
        for log in logs: log.close()
        try:
            for suffix in ('text', 'image', 'video', 'dead'): broker('DELETE', f'queues/%2F/{namespace}.mock.{suffix}')
            for suffix in ('tasks', 'dead'): broker('DELETE', f'exchanges/%2F/{namespace}.mock.{suffix}')
        finally:
            sql('drop schema if exists ' + schema + ' cascade')
            (runtime / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
            print('Phase 8 verification artifacts:', runtime, flush=True)


if __name__ == '__main__':
    main()
