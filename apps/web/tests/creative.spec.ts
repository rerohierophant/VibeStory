import { test, expect } from '@playwright/test';
import { writeFile } from 'node:fs/promises';

test('import long manuscript and hand off between persistent creative agents and media tools', async ({ page, context, baseURL }, testInfo) => {
  await context.route('**/*', route => route.request().url().startsWith(baseURL + '/') ? route.continue() : route.abort());
  await page.goto('/login');
  await page.getByLabel('邮箱', { exact: true }).fill(process.env.WEB_TEST_EMAIL!);
  await page.getByLabel('密码', { exact: true }).fill(process.env.WEB_TEST_PASSWORD!);
  await page.getByRole('button', { name: '进入工作台' }).click();
  await expect(page.getByText('后端已连接', { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const headers = { [csrf.headerName]: csrf.token };
  const projectResponse = await page.request.post('/api/projects', { headers, data: { name: '整稿创作验收', visualType: 'realistic' } });
  expect(projectResponse.ok(), await projectResponse.text()).toBe(true);
  const projectId = (await projectResponse.json()).id;
  const projectPath = `/api/projects/${projectId}/creative`;
  const opening = '第一集 雨巷\n沈夜走进雨巷，看见路灯下的纸条。\n他打开便利店的门，店员转过头来。';
  const chapter = '许星遥穿过旧宅的长廊，留意墙上的旧钟与刻痕。她停下脚步，仔细核对前一夜留下的纸条。';
  const manuscript = opening + '\n\n第二集 旧宅\n' + Array.from({ length: 210 }, (_, i) => `${i + 1}　${chapter}`).join('\n');
  expect(manuscript.length).toBeGreaterThan(6000);
  await page.goto(`/projects/${projectId}`);
  const importer = page.getByRole('region', { name: '整稿分集' });
  await importer.getByLabel('整稿文件').setInputFiles({ name: '雨巷长稿.md', mimeType: 'text/markdown', buffer: Buffer.from(manuscript) });
  await expect(importer.getByText(/雨巷长稿\.md/)).toBeVisible();
  await importer.getByRole('button', { name: '生成分集草案' }).click();
  await expect(importer.getByText(/分集草案包含/)).toBeVisible();
  await importer.getByText('核对原文边界', { exact: true }).first().click();
  await expect(importer.getByText(/开头.*第一集 雨巷/)).toBeVisible();
  const planned = await (await page.request.get(projectPath)).json();
  const planPreview = await (await page.request.get(`${projectPath}/plan/${planned.plan.id}`)).json();
  expect(planPreview.episodes.at(-1).end).toBe(manuscript.length);
  const accepting = page.waitForResponse(response => response.url().endsWith('/accept') && response.request().method() === 'POST');
  await importer.getByRole('button', { name: '确认草案并创建分集' }).click();
  const acceptedResponse = await accepting;
  expect(acceptedResponse.ok(), await acceptedResponse.text()).toBe(true);
  const accepted = await acceptedResponse.json();
  const episodeId = accepted.appliedIds[0];
  const episodePath = `/api/episodes/${episodeId}/creative`;
  await page.goto(`/projects/${projectId}/episodes/${episodeId}/story`);
  const creative = page.getByRole('region', { name: '本集创作会话' });
  await creative.getByRole('button', { name: '整理本集剧本' }).click();
  await expect(creative.getByRole('button', { name: '审阅脚本' })).toBeVisible();
  const initial = await (await page.request.get(episodePath)).json();
  const initialScript = await (await page.request.get(`${episodePath}/script/${initial.script.id}`)).json();
  await creative.getByRole('button', { name: '审阅脚本' }).click();
  await expect(creative.getByText(/沈夜走进雨巷/)).toBeVisible();

  const openAgent = () => page.getByRole('button', { name: 'Agent', exact: true }).click();
  await openAgent();
  const agent = page.getByRole('complementary', { name: 'Agent 对话' });
  const send = async (instruction: string) => {
    const response = page.waitForResponse(r => r.url().endsWith('/messages') && r.request().method() === 'POST', { timeout: 15000 });
    await agent.getByLabel('Agent 指令').fill(instruction);
    await agent.getByRole('button', { name: '发送', exact: true }).click();
    const reply = await response;
    expect(reply.ok(), await reply.text()).toBe(true);
    const result = await reply.json();
    expect(result.conversation.id).toBe(initial.mainSessionId);
    expect(result.messages.at(-1).error).toBeUndefined();
    const message = result.messages.at(-1);
    if (message.execution?.id) {
      await expect.poll(async () => (await (await page.request.get(`/api/agent/runs/${message.execution.id}`)).json()).state).toBe('DONE');
      const completed = await (await page.request.get(`/api/agent/conversations/${result.conversation.id}`)).json();
      const receipt = completed.messages.find((item: { id: string }) => item.id === message.id);
      expect(receipt.error).toBeUndefined();
      return receipt.result;
    }
    return message.result;
  };
  await expect(agent.getByRole('heading', { name: /主会话/ })).toBeVisible();
  expect((await send('继续')).message).toContain('剧本已就绪');
  const revision = '整理本集剧本：保留便利店的开场';
  await send(revision);
  await expect.poll(async () => {
    const state = await (await page.request.get(episodePath)).json();
    return [state.script.previousId, state.script.state];
  }).toEqual([initial.script.id, 'READY']);
  const revised = await (await page.request.get(episodePath)).json();
  const revisedScript = await (await page.request.get(`${episodePath}/script/${revised.script.id}`)).json();
  await send('规划本集分镜');
  await expect(creative.getByRole('button', { name: '审阅分镜草案' })).toBeVisible();
  const state = await (await page.request.get(episodePath)).json();
  expect(new Set([state.mainSessionId, state.scriptSessionId, state.storyboardSessionId]).size).toBe(3);
  expect(state.storyboard.scriptId).toBe(revised.script.id);
  expect(JSON.stringify(state)).not.toContain('沈夜走进雨巷，看见路灯下的纸条');
  const storyboard = await (await page.request.get(`${episodePath}/storyboard/${state.storyboard.id}`)).json();

  await page.reload();
  await openAgent();
  await expect(agent.getByRole('heading', { name: /主会话/ })).toBeVisible();
  expect((await send('继续')).message).toContain(state.storyboard.id);
  await agent.getByRole('button', { name: '收起 Agent' }).click();
  await creative.getByRole('button', { name: '审阅分镜草案' }).click();
  await creative.getByRole('button', { name: '应用本集分镜草案' }).click();
  await expect(creative.getByText(/已应用/)).toBeVisible();
  await openAgent();
  await send('确认全部镜头');
  const image = await send('给第 1 镜生成分镜图');
  await expect.poll(async () => (await (await page.request.get(`/api/tasks/${image.taskId}`)).json()).ready).toBe(true);
  const video = await send('给第 1 镜生成视频');
  await expect.poll(async () => (await (await page.request.get(`/api/tasks/${video.taskId}`)).json()).ready).toBe(true);
  const shots = await (await page.request.get(`/api/episodes/${episodeId}/shots`)).json();
  expect(shots[0].video.basedOn).toBe(shots[0].selectedImageId);

  const episodeSource = (await (await page.request.get(`/api/episodes/${episodeId}`)).json()).story;
  await writeFile(testInfo.outputPath('token-fixture.json'), JSON.stringify({
    manuscript, episodeSource, planPreview, initial, initialScript, revised, revisedScript,
    storyboard, state, revision, planned, accepted,
  }, null, 2) + '\n');
});
