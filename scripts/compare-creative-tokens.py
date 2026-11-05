#!/usr/bin/env python3
"""Replay the same Mock acceptance artifacts through two prompt-packing strategies."""
import argparse
import hashlib
import json
import re
import textwrap
from pathlib import Path

import tiktoken

ROOT = Path(__file__).resolve().parents[1]
SKILL = ROOT / 'apps/agent/src/main/java/com/vibestory/agent/CreativeSkill.java'
ENCODER = 'cl100k_base'
MAIN = '你负责推进创作工作流。根据用户要求、状态和产物选择可执行的下一步，保留人工确认。'
TOOLS = [{'type': 'function', 'function': {'name': 'suggest_workbench_command',
          'description': '返回一条可核对的工作台指令',
          'parameters': {'type': 'object', 'properties': {'instruction': {'type': 'string'}},
                         'required': ['instruction'], 'additionalProperties': False}}}]


def wire(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def windows(source, width):
    cursor = 0
    while cursor < len(source):
        end = min(cursor + width, len(source))
        if end < len(source):
            boundary = source.rfind('\n', cursor, end) + 1
            if boundary > cursor + width // 2:
                end = boundary
        lines = re.findall(r'[^\n]*\n|[^\n]+$', source[cursor:end])
        yield end, [{'block': index, 'text': line} for index, line in enumerate(lines, 1)]
        cursor = end


def replay(fixture, isolated):
    source = fixture['manuscript']
    roles = SKILL.read_text()
    width = int(re.search(r'WINDOW = (\d+)', roles)[1])
    prompts = {role: textwrap.dedent(re.search(r'case ' + role + r' -> """\n(.*?)"""', roles, re.S)[1])
               for role in ('PLAN', 'SCRIPT', 'STORYBOARD')}
    encoder = tiktoken.get_encoding(ENCODER)
    history = []
    measurements = []

    def call(stage, kind, prompt, payload, result):
        message = {'role': 'user', 'content': wire(payload)}
        messages = [{'role': 'system', 'content': prompt}, *history, message]
        request = {'messages': messages}
        if kind == 'main':
            request['tools'] = TOOLS
        else:
            request['response_format'] = {'type': 'json_object'}
        measurements.append({'stage': stage, 'role': kind, 'inputTokens': len(encoder.encode(wire(request)))})
        if not isolated:
            history.extend([message, {'role': 'assistant', 'content': wire(result)}])

    def main(stage, instruction, context, next_action):
        call(stage, 'main', MAIN, {'instruction': instruction, 'state': context}, {'action': next_action})

    def child(stage, role, payload, result):
        call(stage, 'child', prompts[role], payload, result)

    plan = fixture['planned']['plan']
    main('规划分集', '规划项目分集', {'source': fixture['planned']['source']}, 'dispatch_plan')
    start = 0
    continuity = ''
    for index, (end, blocks) in enumerate(windows(source, width), 1):
        drafts = [draft for draft in fixture['planPreview']['episodes'] if start < draft['end'] <= end]
        # The workflow computes offsets from numbered blocks. Only the model's cuts enter the transcript.
        boundaries = {}
        offset = start
        for block in blocks:
            offset += len(block['text'])
            boundaries[offset] = block['block']
        cuts = [{'lastBlock': boundaries[draft['end']], 'title': draft['title'], 'summary': draft['summary']} for draft in drafts]
        child(f'分集窗口 {index}', 'PLAN', {'blocks': blocks, 'instruction': '', 'priorEpisode': continuity}, {'cuts': cuts})
        if drafts:
            continuity = drafts[-1]['summary']
        start = end
    main('审阅建集', '确认分集草案', {'plan': plan}, 'accept_plan')
    main('选集并整理剧本', '整理本集剧本', {'episodeId': fixture['accepted']['appliedIds'][0]}, 'dispatch_script')
    child('剧本初稿', 'SCRIPT', {'source': fixture['episodeSource'], 'instruction': '', 'previousScript': ''}, fixture['initialScript'])
    main('修订剧本', fixture['revision'], {'script': fixture['initial']['script']}, 'dispatch_script_revision')
    notes = fixture['revision'].split('：', 1)[1]
    script_payload = {'source': fixture['episodeSource'], 'instruction': notes}
    if isolated:
        script_payload['previousScript'] = fixture['initialScript']['script']
    child('剧本修订', 'SCRIPT', script_payload, fixture['revisedScript'])
    main('规划分镜', '规划本集分镜', {'script': fixture['revised']['script']}, 'dispatch_storyboard')
    board_payload = {'scenes': [], 'characters': [], 'instruction': '', 'previousStoryboard': []}
    if isolated:
        board_payload['script'] = fixture['revisedScript']['script']
    child('分镜草案', 'STORYBOARD', board_payload, {'shots': fixture['storyboard']['shots']})
    main('应用分镜', '应用分镜草案', {'storyboard': fixture['state']['storyboard']}, 'apply_storyboard')
    main('提交媒体', '给第 1 镜生成分镜图和视频', {'shot': 1, 'storyboardId': fixture['storyboard']['id']}, 'submit_media')
    parent = [row['inputTokens'] for row in measurements if row['role'] == 'main']
    return {'orchestrationPeakInput': max(parent), 'orchestrationTotalInput': sum(parent),
            'workflowTotalInput': sum(row['inputTokens'] for row in measurements), 'calls': measurements}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixture', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    raw = args.fixture.read_bytes()
    fixture = json.loads(raw)
    single = replay(fixture, False)
    split = replay(fixture, True)
    reductions = {key: round(100 * (1 - split[key] / single[key]), 2)
                  for key in ('orchestrationPeakInput', 'orchestrationTotalInput', 'workflowTotalInput')}
    report = {'fixtureSha256': hashlib.sha256(raw).hexdigest(), 'sourceCharacters': len(fixture['manuscript']),
              'encoding': ENCODER, 'tokenizerVersion': tiktoken.__version__,
              'method': 'offline token estimate over reconstructed JSON requests using identical Mock artifacts',
              'singleConversation': single, 'isolatedSessions': split, 'reductionPercent': reductions}
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps({key: [single[key], split[key], reductions[key]] for key in reductions}, ensure_ascii=False))


if __name__ == '__main__':
    main()
