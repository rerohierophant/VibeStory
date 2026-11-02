#!/usr/bin/env python3
"""Real PostgreSQL + isolated RabbitMQ + two JVM Workers. Every provider is Mock.

Requires TEST_RABBITMQ_PORT, TEST_RABBITMQ_MANAGEMENT_PORT and TEST_RABBITMQ_NODE.
The named broker must be a disposable local vibestory* test node: stop_app/start_app
is used for a real restart. No shared/default RabbitMQ node is ever stopped.
"""
import concurrent.futures
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
from base64 import b64encode
from verify_phase7_support import Client, until, free_port, wait_url

ROOT = Path(__file__).resolve().parents[1]


def main():
    stamp = uuid.uuid4().hex[:10]
    runtime = ROOT / 'data/verification' / ('phase7-' + stamp)
    runtime.mkdir(parents=True)
    jar = runtime / 'vibestory-api.jar'
    shutil.copy2(ROOT / 'apps/api/build/libs/vibestory-api.jar', jar)
    node = os.environ['TEST_RABBITMQ_NODE']
    assert node.startswith('vibestory') and '@' in node, 'Use a disposable local vibestory* broker node'
    broker_port = int(os.environ['TEST_RABBITMQ_PORT'])
    management_port = int(os.environ['TEST_RABBITMQ_MANAGEMENT_PORT'])
    assert broker_port != 5672 and management_port != 15672, 'Acceptance must use isolated broker ports'
    namespace = 'vibestory-accept-' + stamp
    email, password = f'mq-{stamp}@example.test', secrets.token_urlsafe(24)
    java = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip() + '/bin/java'
    api_port, web_port = free_port(), free_port()
    env = {**os.environ, 'TEXT_PROVIDER': 'mock', 'IMAGE_PROVIDER': 'mock', 'VIDEO_PROVIDER': 'mock', 'MOCK_ONLY': 'true',
           'TEXT_API_KEY': '', 'IMAGE_API_KEY': '', 'VIDEO_API_KEY': '', 'ADMISSION_ENABLED': 'false', 'DISPATCH_MODE': 'rabbit', 'WORKER_ENABLED': 'true',
           'RABBITMQ_PORT': str(broker_port), 'MQ_NAMESPACE': namespace, 'SEED_EMAIL': '', 'SEED_PASSWORD': '',
           'STORAGE_ROOT': str(runtime / 'storage')}
    database = urllib.parse.urlparse(env['DATABASE_URL'].removeprefix('jdbc:'))
    db_env = {**env, 'PGHOST': database.hostname or 'localhost', 'PGPORT': str(database.port or 5432),
              'PGDATABASE': database.path.lstrip('/'), 'PGUSER': env.get('DATABASE_USER', 'vibestory'), 'PGPASSWORD': env.get('DATABASE_PASSWORD', '')}
    assert not database.query, 'Use a plain DATABASE_URL; acceptance creates its own schema'
    schema = 'vibestory_mq_' + stamp
    db_env['PGOPTIONS'] = '-c search_path=' + schema
    env['DATABASE_URL'] += '?currentSchema=' + schema
    env['SPRING_FLYWAY_SCHEMAS'] = schema
    psql = shutil.which('psql') or '/opt/homebrew/opt/postgresql@16/bin/psql'
    ctl = shutil.which('rabbitmqctl') or '/opt/homebrew/opt/rabbitmq/sbin/rabbitmqctl'
    processes, logs = [], []
    summary = {'providers': ['mock', 'mock', 'mock'], 'realGenerationEnabled': False, 'namespace': namespace, 'checks': []}
    api_process = None
    worker1 = worker2 = None
    broker_stopped = False

    def sql(query):
        return subprocess.check_output([psql, '-X', '-Atq', '-v', 'ON_ERROR_STOP=1', '-c', query], env=db_env, text=True).strip()

    def passed(name):
        summary['checks'].append(name)
        print('PASS:', name, flush=True)

    def broker(method, path, body=None):
        credentials = b64encode((env.get('RABBITMQ_USER', 'guest') + ':' + env.get('RABBITMQ_PASSWORD', 'guest')).encode()).decode()
        request = urllib.request.Request(f'http://127.0.0.1:{management_port}/api/' + path,
            data=None if body is None else json.dumps(body).encode(), method=method,
            headers={'Authorization': 'Basic ' + credentials, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=10) as response:
            payload = response.read()
            return json.loads(payload) if payload else None

    def control(command):
        with (runtime / ('broker-' + command + '.log')).open('wb') as log:
            subprocess.run([ctl, '-n', node, command], env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=45)

    def boot(role, name, delay='4s'):
        port = api_port if role == 'api' else free_port()
        log = (runtime / (name + '.log')).open('wb'); logs.append(log)
        process = subprocess.Popen([java, '-Djava.awt.headless=true', '-jar', str(jar),
            f'--server.port={port}', '--vibestory.worker.lease-duration=5s', '--vibestory.worker.heartbeat-interval=1s',
            '--vibestory.dispatch.prefetch=1', '--vibestory.dispatch.text-slots=1', '--vibestory.dispatch.image-slots=1', '--vibestory.dispatch.video-slots=1',
            '--vibestory.mock.delay=' + delay, '--vibestory.mock.failure-rate=0',
            '--vibestory.seed.email=' + (email if role == 'api' else ''), '--vibestory.seed.password=' + (password if role == 'api' else '')],
            cwd=ROOT, env={**env, 'APP_ROLE': role}, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        wait_url(f'http://127.0.0.1:{port}/v3/api-docs', process)
        return process

    def stop(process, kill=False):
        if process and process.poll() is None:
            process.kill() if kill else process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()

    def login():
        client = Client(f'http://127.0.0.1:{api_port}')
        until(lambda: sql(f"select count(*) from app_user where email='{email}'") == '1')
        client.login(email, password)
        return client

    def ready(client, task):
        return until(lambda: value if (value := client.request('GET', f'/api/tasks/{task}'))['ready'] else None, timeout=60)

    def event(task):
        return json.loads(sql(f"""select json_build_object('schemaVersion',1,'eventId',o.id,'taskId',t.id,'dispatchGeneration',t.dispatch_generation,
            'type',t.type,'userId',t.user_id,'projectId',t.project_id,'episodeId',t.episode_id) from task t join task_outbox o
            on o.task_id=t.id and o.dispatch_generation=t.dispatch_generation where t.id={int(task)}"""))

    def publish(notification, routing='video'):
        result = broker('POST', f'exchanges/%2F/{namespace}.mock.tasks/publish',
            {'properties': {'delivery_mode': 2, 'content_type': 'application/json'}, 'routing_key': routing,
             'payload': json.dumps(notification), 'payload_encoding': 'string'})
        assert result['routed']

    try:
        sql('create schema ' + schema)
        summary['rabbitmqVersion'] = broker('GET', 'overview')['rabbitmq_version']
        api_process = boot('api', 'api')
        client = login(); user = int(client.request('GET', '/api/me')['id'])
        project = client.request('POST', '/api/projects', {'name': 'MQ acceptance ' + stamp, 'visualType': 'realistic', 'style': ''}, 201)
        episode = client.request('POST', f"/api/projects/{project['id']}/episodes", {'title': '批次'}, 201)
        pid, eid = int(project['id']), int(episode['id'])
        shots = json.loads(sql(f"""with inserted as (insert into shot(user_id,project_id,episode_id,seq,status,description)
            select {user},{pid},{eid},n,case when n=6 then 'DRAFT' else 'CONFIRMED' end,'Mock batch shot '||n from generate_series(1,6) n returning id,seq)
            select json_agg(json_build_object('id',id,'seq',seq) order by seq) from inserted"""))
        for shot in shots[:4]: sql(f"insert into shot_image(shot_id,episode_id,selected,storage_key) values ({shot['id']},{eid},true,'mock-logical-first-frame')")
        body = {'type': 'VIDEO', 'requestKey': 'stable-' + stamp, 'shotIds': [str(s['id']) for s in shots]}
        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
            views = list(pool.map(lambda _: client.request('POST', f'/api/episodes/{eid}/generation-batches', body, 202), range(10)))
        assert len({v['id'] for v in views}) == 1
        view = views[0]; batch_id = view['id']; task_ids = [m['taskId'] for m in view['members'] if m.get('taskId')]
        assert view['progress']['accepted'] == 4 and view['progress']['blocked'] == 2
        assert sql(f"select count(*) from task_attempt a join task t on t.id=a.task_id where t.project_id={pid}") == '0'
        passed('API role accepts durable batch without executing; ten requests create one batch / four tasks; two blockers')
        # Change the page selection after submission; all task snapshots must remain fixed.
        first = shots[0]['id']
        old_image = sql(f'select id from shot_image where shot_id={first} and selected')
        sql(f'update shot_image set selected=false where shot_id={first}')
        sql(f"insert into shot_image(shot_id,episode_id,version,selected,storage_key) values ({first},{eid},2,true,'new-selection')")
        worker1 = boot('worker', 'worker-1')
        worker2 = boot('worker', 'worker-2')
        publish(event(task_ids[0])); publish(event(task_ids[0]))
        for task in task_ids: assert ready(client, task)['attempts'] == 1
        completed = client.request('GET', '/api/generation-batches/' + batch_id)
        assert completed['state'] == 'complete' and completed['progress']['succeeded'] == 4
        assert int(sql(f"select count(distinct split_part(a.lease_owner,':',1)) from task_attempt a join task t on t.id=a.task_id where t.project_id={pid}")) == 2
        assert sql(f'select source_image_id from video where task_id={int(task_ids[0])}') == old_image
        until(lambda: client.request('GET', '/api/dispatch-metrics')['duplicates'] >= 2)
        passed('two independent Workers finish one attempt per task; duplicate messages are ACKed; first-frame source stays frozen')
        # A real process SIGKILL after ACK must recover through the SQL lease, not broker requeue.
        stop(worker2)
        crash = client.request('POST', f'/api/shots/{first}/video-tasks', {'regenerateNonce': 'crash-' + stamp}, 202)['taskId']
        until(lambda: client.request('GET', f'/api/tasks/{crash}')['state'] == 'running')
        stop(worker1, kill=True)
        worker2 = boot('worker', 'worker-recovery')
        recovered = ready(client, crash); assert recovered['attempts'] == 2
        assert [a['state'] for a in recovered['history']] == ['abandoned', 'succeeded']
        passed('SIGKILL after claim/ACK is taken over by another JVM with a fenced lease and two recorded Mock attempts')
        # Stop only the disposable test node's application; preserve its durable data and restart it.
        control('stop_app'); broker_stopped = True
        outage = client.request('POST', f'/api/shots/{first}/video-tasks', {'regenerateNonce': 'outage-' + stamp}, 202)['taskId']
        until(lambda: int(sql(f'select publish_failures from task_outbox where task_id={int(outage)}')) >= 1)
        assert client.request('GET', f'/api/tasks/{outage}')['attempts'] == 0
        control('start_app'); broker_stopped = False
        assert ready(client, outage)['attempts'] == 1
        passed('RabbitMQ outage/restart keeps the HTTP submission and Outbox; confirmed retry produces one business attempt')
        # Arrange exactly one poison notification with the current durable identity.
        stop(worker2)
        poison = client.request('POST', f'/api/shots/{first}/video-tasks', {'regenerateNonce': 'poison-' + stamp}, 202)['taskId']
        until(lambda: sql(f"select status from task_outbox where task_id={int(poison)}") == 'SENT')
        sql(f"update task_outbox set status='BLOCKED' where task_id={int(poison)}")
        broker('DELETE', f'queues/%2F/{namespace}.mock.video/contents')
        invalid = event(poison); invalid['schemaVersion'] = 99; publish(invalid)
        worker1 = boot('worker', 'worker-poison')
        until(lambda: sql(f"select coalesce(dispatch_blocked,'') from task where id={int(poison)}") == 'DEAD_LETTER')
        assert client.request('GET', f'/api/tasks/{poison}')['attempts'] == 0
        time.sleep(3)
        assert sql(f"select status from task_outbox where task_id={int(poison)}") == 'BLOCKED'
        client.request('POST', f'/api/tasks/{poison}/dispatch/resume', {})
        assert ready(client, poison)['attempts'] == 1
        assert event(poison)['dispatchGeneration'] == invalid['dispatchGeneration'] + 1
        passed('invalid contract goes through real DLX, durably pauses dispatch, and resumes explicitly without a phantom attempt')
        stop(api_process); api_process = boot('api', 'api-restarted'); client = login()
        assert client.request('GET', '/api/generation-batches/' + batch_id)['progress']['succeeded'] == 4
        passed('API restart restores batch members/results from PostgreSQL')
        # The same page test also runs in the database baseline; here it uses real MQ and two JVMs.
        worker2 = boot('worker', 'worker-browser', '500ms')
        browser_env = {**env, 'WEB_TEST_URL': f'http://127.0.0.1:{web_port}', 'API_PROXY_TARGET': f'http://127.0.0.1:{api_port}',
                       'WEB_TEST_EMAIL': email, 'WEB_TEST_PASSWORD': password, 'WEB_TEST_ARTIFACTS': str(runtime / 'browser')}
        log = (runtime / 'web.log').open('wb'); logs.append(log)
        web = subprocess.Popen(['npm', 'run', 'dev', '--', '--host', '127.0.0.1', '--port', str(web_port), '--strictPort'], cwd=ROOT / 'apps/web', env=browser_env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(web); wait_url(browser_env['WEB_TEST_URL'], web)
        subprocess.run(['npx', 'playwright', 'test', 'batches.spec.ts'], cwd=ROOT / 'apps/web', env=browser_env, check=True)
        passed('Chrome batch image/video submission, per-member blockers and refresh recovery through real MQ')
        summary['metrics'] = client.request('GET', '/api/dispatch-metrics')
        summary['status'] = 'passed'
    except Exception:
        summary['status'] = 'failed'
        raise
    finally:
        if broker_stopped: control('start_app')
        for process in reversed(processes): stop(process)
        for log in logs: log.close()
        try:
            for suffix in ('text', 'image', 'video', 'dead'): broker('DELETE', f'queues/%2F/{namespace}.mock.{suffix}')
            for suffix in ('tasks', 'dead'): broker('DELETE', f'exchanges/%2F/{namespace}.mock.{suffix}')
        except Exception: pass
        sql('drop schema if exists ' + schema + ' cascade')
        (runtime / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
        print('Phase 7 verification artifacts:', runtime, flush=True)


if __name__ == '__main__':
    main()
