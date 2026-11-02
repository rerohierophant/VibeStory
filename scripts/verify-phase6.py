#!/usr/bin/env python3
"""All-Mock closeout: durable tests, contracts, browser workflow and crash recovery. No recording."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    runtime = ROOT / 'data/verification' / ('phase6-' + uuid.uuid4().hex[:10])
    runtime.mkdir(parents=True)
    env = {**os.environ, 'ADMISSION_ENABLED': 'false', 'DISPATCH_MODE': 'database', 'APP_ROLE': 'all', 'TEXT_PROVIDER': 'mock', 'IMAGE_PROVIDER': 'mock', 'VIDEO_PROVIDER': 'mock',
           'MOCK_ONLY': 'true', 'TEXT_API_KEY': '', 'IMAGE_API_KEY': '', 'VIDEO_API_KEY': ''}
    summary = {'startedAt': datetime.now(timezone.utc).isoformat(), 'providers': ['mock', 'mock', 'mock'],
               'realGenerationEnabled': False, 'recording': False, 'steps': []}
    summary_file = runtime / 'summary.json'

    def step(name, command, cwd=ROOT):
        print('Running:', name, flush=True)
        path = runtime / (name + '.log')
        with path.open('wb') as log:
            result = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        summary['steps'].append({'name': name, 'exitCode': result.returncode, 'log': str(path)})
        summary_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
        if result.returncode:
            raise RuntimeError(f'{name} failed; inspect {path}')
        print('PASS:', name, flush=True)
        return path

    try:
        step('backend', [str(ROOT / 'scripts/java21.sh'), 'test', 'bootJar'])
        results = [ET.parse(path).getroot() for path in (ROOT / 'apps/api/build/test-results/test').glob('TEST-*.xml')]
        summary['backend'] = {key: sum(int(result.attrib.get(key, 0)) for result in results) for key in ['tests', 'failures', 'errors', 'skipped']}
        assert results and summary['backend']['failures'] == summary['backend']['errors'] == summary['backend']['skipped'] == 0
        step('openapi', [sys.executable, 'scripts/export-openapi.py'])
        step('contracts', ['npm', 'run', 'generate'], ROOT / 'packages/contracts')
        step('components', ['npm', 'test', '--', '--reporter=json', f'--outputFile={runtime / "components.json"}'], ROOT / 'apps/web')
        component_results = json.loads((runtime / 'components.json').read_text())
        summary['components'] = {'tests': component_results['numTotalTests'], 'passed': component_results['numPassedTests']}
        step('component-types', ['npm', 'run', 'test:types'], ROOT / 'apps/web')
        step('web-build', ['npm', 'run', 'build'], ROOT / 'apps/web')
        step('http-crash-recovery', [sys.executable, 'scripts/verify-phase1.py', '--phase2', '--phase3', '--phase4'])
        browser_log = step('browser', [sys.executable, 'scripts/verify-web.py'])
        artifact_line = next(line for line in browser_log.read_text().splitlines() if line.startswith('Browser verification artifacts:'))
        browser_dir = Path(artifact_line.partition(':')[2].strip())
        browser_results = json.loads((browser_dir / 'browser/results.json').read_text())
        summary['browser'] = browser_results['stats']
        summary['browserArtifacts'] = str(browser_dir)
        assert browser_results['stats']['unexpected'] == browser_results['stats']['flaky'] == browser_results['stats']['skipped'] == 0
        step('diff-check', ['git', 'diff', '--check'])
        summary['status'] = 'passed'
    except Exception:
        summary['status'] = 'failed'
        raise
    finally:
        summary['finishedAt'] = datetime.now(timezone.utc).isoformat()
        summary_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
        print('Closeout summary:', summary_file, flush=True)


if __name__ == '__main__':
    main()
