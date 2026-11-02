#!/usr/bin/env python3
"""Capture springdoc with the Worker disabled. No model calls or fixture accounts."""
import json
import os
import shutil
import platform
from pathlib import Path
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def main():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    java_home = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip() if platform.system() == 'Darwin' else os.environ['JAVA_HOME']
    env = {**os.environ, 'DISPATCH_MODE': 'database', 'APP_ROLE': 'all', 'WORKER_ENABLED': 'false', 'SEED_EMAIL': '', 'SEED_PASSWORD': '',
           'TEXT_PROVIDER': 'mock', 'IMAGE_PROVIDER': 'mock', 'VIDEO_PROVIDER': 'mock', 'MOCK_ONLY': 'true',
           'TEXT_API_KEY': '', 'IMAGE_API_KEY': '', 'VIDEO_API_KEY': ''}
    runtime = ROOT / 'data/verification/contracts'
    runtime.mkdir(parents=True, exist_ok=True)
    jar = runtime / 'vibestory-api.jar'
    shutil.copy2(ROOT / 'apps/api/build/libs/vibestory-api.jar', jar)
    with (runtime / 'boot.log').open('wb') as log:
        process = subprocess.Popen([java_home + '/bin/java', '-jar', str(jar), f'--server.port={port}'],
                                   env=env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while True:
                assert process.poll() is None, 'API exited; see the ignored contract boot log'
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{port}/v3/api-docs', timeout=3) as response:
                        spec = json.load(response)
                    break
                except OSError:
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(.2)
            spec['servers'] = [{'url': '/', 'description': 'Current API origin'}]
            (ROOT / 'packages/contracts/openapi.json').write_text(json.dumps(spec, ensure_ascii=False, indent=2, sort_keys=True) + '\n')
            print('Captured springdoc', spec['info']['version'], len(spec['paths']), 'paths; Worker disabled')
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

if __name__ == '__main__':
    main()
