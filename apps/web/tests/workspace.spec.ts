import { test, expect, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.beforeEach(async ({ context, baseURL }) => {
  // A browser regression must not contact a supplier or any other external origin.
  await context.route('**/*', route => {
    const url = route.request().url();
    return url.startsWith(baseURL + '/') || url.startsWith('data:') || url.startsWith('blob:')
      ? route.continue() : route.abort('blockedbyclient');
  });
});

async function login(page: Page, email = process.env.WEB_TEST_EMAIL!) {
  await page.goto('/login');
  await page.getByLabel('邮箱', { exact: true }).fill(email);
  await page.getByLabel('密码', { exact: true }).fill(process.env.WEB_TEST_PASSWORD!);
  await page.getByRole('button', { name: '进入工作台' }).click();
  await expect(page.getByText('后端已连接', { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
}

test('browser workflow persists projects, edits, asset uploads, images and video with fixed first-frame source', async ({ page }, testInfo) => {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
  await login(page);
  const runtime = await (await page.request.get('/api/runtime')).json();
  expect([runtime.text.provider, runtime.image.provider, runtime.video.provider]).toEqual(['mock', 'mock', 'mock']);
  await page.getByRole('button', { name: '新建项目', exact: true }).first().click();
  await page.getByPlaceholder('例如：长夜将明').fill('浏览器联调验收');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await expect(page).toHaveURL(/\/projects\/\d+$/);
  const projectId = new URL(page.url()).pathname.split('/')[2];
  await page.getByRole('button', { name: '新建一集', exact: true }).first().click();
  await expect(page).toHaveURL(/\/episodes\/\d+\/story$/);
  const episodeId = new URL(page.url()).pathname.split('/')[4];
  const story = '雨夜街道，沈夜收起伞走向便利店。店员抬起头，窗边的路灯把积水照得发亮。\n\n许星遥放下牛奶，低声说：「你终于回来了。」沈夜站在门口，看着玻璃窗上滑落的雨水。';
  const textarea = page.getByPlaceholder(/把这一集的剧本贴在这里/);
  await textarea.fill(story);
  // Click immediately: the action must flush the debounced story write before submitting breakdown.
  await page.getByRole('button', { name: 'AI 拆解镜头', exact: true }).click();
  await expect(page.getByRole('button', { name: '重新拆解', exact: true })).toBeVisible();
  await page.getByRole('link', { name: '分镜', exact: true }).click();
  const row = page.locator('tbody tr').first();
  await expect(row.getByRole('button', { name: '生成分镜图', exact: true })).toBeDisabled();
  await row.getByRole('button', { name: '编辑镜头', exact: true }).click();
  await page.getByLabel('画面描述', { exact: true }).fill('浏览器人工编辑：沈夜在便利店门前停步。');
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await expect(row).toContainText('浏览器人工编辑');
  await row.getByRole('button', { name: '确认', exact: true }).click();
  await expect(row.getByRole('button', { name: '生成分镜图', exact: true })).toBeEnabled();

  // Upload a real file through the page, then freeze that asset version in the next image task.
  await page.goto(`/projects/${projectId}/scenes`);
  const assetRow = page.locator('article').first();
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');
  await assetRow.locator('input[type=file]').setInputFiles({ name: '场景参考.png', mimeType: 'image/png', buffer: png });
  await expect(assetRow).toContainText('1 个版本');
  await page.goto(`/projects/${projectId}/episodes/${episodeId}/storyboard`);
  await row.getByRole('button', { name: '生成分镜图', exact: true }).click();
  await expect(row.locator('img').first()).toBeVisible();
  const shots = await (await page.request.get(`/api/episodes/${episodeId}/shots`)).json();
  const shotId = shots[0].id, firstImage = shots[0].selectedImageId;
  expect(shots[0].images[0].inputSnapshot.scene.versionId).toBeTruthy();
  await row.getByRole('button', { name: '再生成一版', exact: true }).click();
  await expect.poll(async () => (await (await page.request.get(`/api/shots/${shotId}`)).json()).images.length).toBe(2);
  await expect(row.getByRole('button', { name: /选定 候选 2$/ })).toBeVisible();
  await row.getByRole('button', { name: /选定 候选 2$/ }).click();
  await expect.poll(async () => (await (await page.request.get(`/api/shots/${shotId}`)).json()).selectedImageId).not.toBe(firstImage);
  const selected = await (await page.request.get(`/api/shots/${shotId}`)).json();
  expect(selected.selectedImageId).not.toBe(firstImage);
  await page.getByRole('link', { name: '视频', exact: true }).click();
  await page.getByRole('button', { name: '生成视频', exact: true }).first().click();
  await expect.poll(async () => (await (await page.request.get(`/api/shots/${shotId}`)).json()).video.taskId).toBeTruthy();
  await page.goto(`/projects/${projectId}/episodes/${episodeId}/storyboard`);
  await row.getByRole('button', { name: /选定 候选 1$/ }).click();
  await expect.poll(async () => (await (await page.request.get(`/api/shots/${shotId}`)).json()).selectedImageId).toBe(firstImage);
  await expect.poll(async () => (await (await page.request.get(`/api/shots/${shotId}`)).json()).video.ready).toBe(true);
  const ready = await (await page.request.get(`/api/shots/${shotId}`)).json();
  expect(ready.selectedImageId).toBe(firstImage); expect(ready.video.basedOn).toBe(selected.selectedImageId); expect(ready.video.attempts).toBe(1);
  const metrics = await (await page.request.get('/api/task-metrics')).json();
  const videoMetrics = metrics.groups.find((group: { type: string }) => group.type === 'VIDEO');
  expect(videoMetrics.provider).toBe('mock'); expect(videoMetrics.succeeded).toBeGreaterThanOrEqual(1);
  expect(videoMetrics.firstQueueWait.samples).toBeGreaterThanOrEqual(1);
  expect(videoMetrics.attemptElapsed.samples).toBeGreaterThanOrEqual(1);
  await page.getByRole('link', { name: '视频', exact: true }).click();
  await page.locator('article').first().getByRole('button', { name: /▶/ }).click();
  const video = page.getByLabel('镜头视频', { exact: true });
  await expect(video).toBeVisible(); await expect.poll(() => video.evaluate((v: HTMLVideoElement) => v.readyState)).toBeGreaterThanOrEqual(1);
  await expect(video).toHaveAttribute('src', /\/api\/tasks\/\d+\/video/);
  await page.screenshot({ path: testInfo.outputPath('connected-video.png'), fullPage: true });
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await page.reload(); await expect(page.locator('article').first()).toContainText('已生成');
  await page.goto(`/projects/${projectId}/episodes/${episodeId}/story`); await expect(textarea).toHaveValue(story);
  await page.getByRole('button', { name: '重新拆解', exact: true }).click();
  await page.getByRole('dialog', { name: '重新拆解这一集？' }).getByRole('button', { name: '取消', exact: true }).click();
  const unchanged = await (await page.request.get(`/api/shots/${shotId}`)).json();
  expect(unchanged.video.ready).toBe(true); expect(unchanged.video.basedOn).toBe(selected.selectedImageId);
  expect(await page.evaluate(() => localStorage.getItem('vibestory.state.v2'))).toBeNull();
  expect(errors).toEqual([]);
});

test('save failure keeps the draft and prevents generation until it is saved', async ({ page }) => {
  await login(page);
  const projects = await (await page.request.get('/api/projects')).json();
  const episode = projects[0].episodes[0];
  await page.goto(`/projects/${projects[0].id}/episodes/${episode.id}/story`);
  let fail = true;
  let breakdownSubmissions = 0;
  await page.route('**/api/episodes/*/breakdown-tasks', route => { breakdownSubmissions++; return route.continue(); });
  await page.route('**/api/episodes/*/story', async route => {
    if (route.request().method() === 'PUT' && fail) return route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ detail: '验收：保存暂不可用' }) });
    return route.continue();
  });
  const field = page.getByPlaceholder(/把这一集的剧本贴在这里/);
  const draft = '保存失败仍需保留的人工草稿，不能被后台轮询覆盖。';
  await field.fill(draft); await expect(page.getByRole('alert')).toContainText('保存暂不可用'); await expect(field).toHaveValue(draft);
  await page.getByRole('button', { name: '重新拆解', exact: true }).click();
  const failedSave = page.waitForResponse(response => response.request().method() === 'PUT' && response.url().endsWith('/story'));
  await page.getByRole('button', { name: '确认重新拆解', exact: true }).click();
  expect((await failedSave).status()).toBe(503);
  expect(breakdownSubmissions).toBe(0); await expect(field).toHaveValue(draft);
  fail = false; await page.getByRole('button', { name: '重试保存 / 刷新' }).click(); await expect(page.getByRole('alert')).toHaveCount(0);
  await page.reload(); await expect(field).toHaveValue(draft);
});

test('Agent previews and saves the third shot through the Mock workbench', async ({ page }) => {
  await login(page);
  const runtime = await (await page.request.get('/api/runtime')).json();
  expect([runtime.text.provider, runtime.image.provider, runtime.video.provider]).toEqual(['mock', 'mock', 'mock']);
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const headers = { [csrf.headerName]: csrf.token };
  const project = await page.request.post('/api/projects', { headers, data: { name: 'Agent 改镜验收', visualType: 'realistic' } });
  expect(project.status()).toBe(201);
  const projectId = (await project.json()).id;
  const episode = await page.request.post(`/api/projects/${projectId}/episodes`, { headers, data: {} });
  expect(episode.status()).toBe(201);
  const episodeId = (await episode.json()).id;
  const path = `/api/episodes/${episodeId}`;
  const originalDialogue = '我们得马上离开这里。再晚一点就来不及了。';
  expect((await page.request.put(`${path}/story`, { headers, data: {
    story: `沈夜走进雨夜街道。\n便利店的门打开。\n沈夜说：「${originalDialogue}」`,
  } })).ok()).toBe(true);
  expect((await page.request.post(`${path}/breakdowns`, { headers, data: { replaceExisting: false } })).status()).toBe(202);
  await expect.poll(async () => (await (await page.request.get(`${path}/shots`)).json()).length).toBe(3);
  const [, , third] = await (await page.request.get(`${path}/shots`)).json();
  const shotPath = `/api/shots/${third.id}`;
  const setup = await page.request.patch(shotPath, { headers, data: { shotSize: '中景', expectedVersion: third.version } });
  expect(setup.ok()).toBe(true);
  const before = await setup.json();
  expect(before.dialogue).toBe(originalDialogue);

  await page.goto(`/projects/${projectId}/episodes/${episodeId}/storyboard`);
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  const panel = page.getByRole('complementary', { name: 'Agent 对话' });
  const proposal = panel.getByRole('region', { name: '改镜提案' }).last();
  const field = panel.getByLabel('Agent 指令');
  const menu = panel.getByRole('listbox', { name: '技能示例' });
  const send = panel.getByRole('button', { name: '发送', exact: true });
  const preview = async (instruction: string) => {
    await field.fill(instruction);
    await send.click();
  };
  await expect(menu).toHaveCount(0);
  await field.press('/');
  await expect(menu.getByRole('group')).toHaveCount(6);
  await expect(send).toBeDisabled();
  await field.press('Escape');
  await expect(menu).toHaveCount(0);

  const example = '把第 3 镜改成近景，台词短一点';
  await field.fill('／镜头编辑');
  await expect(menu.getByRole('group', { name: '镜头编辑与确认' })).toBeVisible();
  await expect(menu.getByRole('group')).toHaveCount(1);
  await field.press('ArrowUp');
  await expect(menu.getByRole('option', { name: example })).toHaveAttribute('aria-selected', 'true');
  await field.press('Enter');
  await expect(field).toHaveValue(example);
  await expect(menu).toHaveCount(0);
  await expect(proposal).toHaveCount(0);
  await send.click();
  await expect(proposal).toContainText('第 3 镜');
  await expect(proposal).toContainText('修改前　中景');
  await expect(proposal).toContainText('修改后　近景');
  await expect(proposal).toContainText(`修改前　${originalDialogue}`);
  await expect(proposal).toContainText('修改后　我们得马上离开这里。');
  expect(await (await page.request.get(shotPath)).json()).toEqual(before);

  await page.reload();
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  const savedResponse = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/apply'));
  await proposal.getByRole('button', { name: '确认保存' }).click();
  const response = await savedResponse;
  expect(response.ok()).toBe(true);
  await expect(proposal.getByRole('status')).toHaveText('已保存第 3 镜');
  const after = await (await page.request.get(shotPath)).json();
  expect(after).toEqual({ ...before, shotSize: '近景', dialogue: '我们得马上离开这里。', version: before.version + 1 });
  const row = page.locator('tbody tr').nth(2);
  await expect(row).toContainText('近景');
  await expect(row).toContainText('我们得马上离开这里。');
  await expect(row).not.toContainText('再晚一点就来不及了。');
  const duplicate = await page.request.post(response.url(), { headers });
  expect(duplicate.ok()).toBe(true);
  expect(await (await page.request.get(shotPath)).json()).toEqual(after);

  await preview('把第 03 镜改成特写');
  await expect(proposal).toContainText('修改后　特写');
  expect((await page.request.patch(shotPath, { headers, data: { dialogue: '等等我。', expectedVersion: after.version } })).ok()).toBe(true);
  await proposal.getByRole('button', { name: '确认保存' }).click();
  await expect(panel.getByRole('alert')).toContainText('请重新预览');
  expect(await (await page.request.get(shotPath)).json()).toMatchObject({ shotSize: '近景', dialogue: '等等我。' });
  expect((await page.request.patch(shotPath, { headers, data: { dialogue: '旧标签页编辑', expectedVersion: after.version } })).status()).toBe(409);
});

test('Agent runs scoped workspace, story, shot, asset, generation and progress commands', async ({ page }) => {
  await login(page);
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const headers = { [csrf.headerName]: csrf.token };
  const project = await page.request.post('/api/projects', { headers, data: { name: '工作台指令验收', visualType: 'realistic' } });
  expect(project.status()).toBe(201);
  const projectId = (await project.json()).id;
  await page.goto(`/projects/${projectId}`);
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  const panel = page.getByRole('complementary', { name: 'Agent 对话' });
  const field = panel.getByLabel('Agent 指令');
  async function send(instruction: string) {
    const pending = page.waitForResponse(response => response.url().endsWith('/messages')
      && response.request().method() === 'POST', { timeout: 15000 });
    await field.fill(instruction);
    await panel.getByRole('button', { name: '发送', exact: true }).click();
    const response = await pending;
    expect(response.ok(), await response.text()).toBe(true);
    const result = (await response.json()).messages.at(-1).result;
    await expect(panel.getByRole('region', { name: '指令结果' }).last().getByRole('status')).toHaveText(result.message);
    return { result };
  }

  const created = await send('新建一集叫《追踪》');
  const episodeId = created.result.episodeId;
  expect((await (await page.request.get(`/api/projects/${projectId}`)).json()).episodes).toHaveLength(1);
  await panel.getByRole('button', { name: '打开分集', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/episodes/${episodeId}/story$`));
  await expect(panel.getByText('工作台指令验收 · 第 1 集', { exact: true })).toBeVisible();
  const story = '沈夜走进雨夜街道，灯光照亮路口。\n沈夜推开便利店的门，低头看见一张旧纸条。';
  await send(`把这段文字设为正文：${story}`);
  const breakdown = await send('拆成镜头给我看看');
  expect(breakdown.result.taskId).toBeTruthy();
  await expect.poll(async () => (await (await page.request.get(`/api/episodes/${episodeId}/shots`)).json()).length).toBe(2);
  await send('确认全部镜头');

  const scenes = await (await page.request.get(`/api/projects/${projectId}/scenes`)).json();
  const scene = scenes[0];
  const asset = await send(`给${scene.name}生成一版场景图`);
  await expect.poll(async () => (await (await page.request.get(`/api/tasks/${asset.result.taskId}`)).json()).ready).toBe(true);
  const selection = await send(`把场景《${scene.name}》的第 1 版设为基准`);
  expect(selection.result.message).toContain('设为基准');

  const batch = await send('给已确认但没图的镜头出图');
  expect(batch.result.batchId).toBeTruthy();
  await expect.poll(async () => (await (await page.request.get(`/api/generation-batches/${batch.result.batchId}`)).json()).progress.succeeded).toBe(2);
  const video = await send('选第 1 镜的第 1 张图做首帧，再生成视频');
  await expect.poll(async () => (await (await page.request.get(`/api/tasks/${video.result.taskId}`)).json()).ready).toBe(true);
  const source = await send('第 1 镜视频用了哪张首帧');
  expect(source.result.message).toContain('候选 1作为首帧');
  const problems = await send('哪些镜头没成功');
  expect(problems.result.details).toEqual([]);

  const requestKey = randomUUID();
  const sendEpisode = (id: string, title: string) => page.request.post(`/api/agent/conversations/${id}/messages`, {
    headers, multipart: { requestKey, projectId, instruction: `新建一集叫《${title}》` },
  });
  const chatId = randomUUID();
  const command = await sendEpisode(chatId, '回执验收');
  const replay = await sendEpisode(chatId, '回执验收');
  expect(command.ok() && replay.ok()).toBe(true);
  expect(await replay.json()).toEqual(await command.json());
  const separateChat = await sendEpisode(randomUUID(), '独立会话');
  expect((await separateChat.json()).messages[0].result.episodeId).toBeTruthy();
  expect((await (await page.request.get(`/api/projects/${projectId}`)).json()).episodes).toHaveLength(3);
});

test('Agent restores the breakdown workflow and generates a new candidate from a confirmed description', async ({ page }) => {
  await login(page);
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const headers = { [csrf.headerName]: csrf.token };
  const project = await page.request.post('/api/projects', { headers, data: { name: 'Agent 流程验收', visualType: 'realistic' } });
  expect(project.status()).toBe(201);
  const projectId = (await project.json()).id;
  const episode = await page.request.post(`/api/projects/${projectId}/episodes`, { headers, data: {} });
  expect(episode.status()).toBe(201);
  const episodeId = (await episode.json()).id;
  const path = `/api/episodes/${episodeId}`;
  const story = '沈夜走进雨夜街道，低头看见积水。\n便利店的门打开，店员望向街边。\n沈夜停在右边的白色路灯下。';
  expect((await page.request.put(`${path}/story`, { headers, data: { story } })).ok()).toBe(true);
  await page.goto(`/projects/${projectId}/episodes/${episodeId}/storyboard`);
  await expect(page).toHaveURL(new RegExp(`/episodes/${episodeId}/story$`));
  const open = () => page.getByRole('button', { name: 'Agent', exact: true }).click();
  await open();
  const panel = page.getByRole('complementary', { name: 'Agent 对话' });
  const workflow = panel.getByRole('region', { name: '拆镜与出图流程' });
  const send = async (instruction: string) => {
    await panel.getByLabel('Agent 指令').fill(instruction);
    await panel.getByRole('button', { name: '发送', exact: true }).click();
  };
  await send('把这集的故事拆成镜头，我确认后给没图的镜头出图');
  const confirm = workflow.getByRole('button', { name: '确认镜头并出图' });
  await expect(confirm).toBeVisible();
  const listBatches = async () => (await page.request.get(`${path}/generation-batches?type=IMAGE`)).json();
  expect(await listBatches()).toEqual([]);

  await page.reload();
  await open();
  const pendingConfirm = page.waitForResponse(response => response.url().includes('/workflows/') && response.url().endsWith('/confirm'));
  await confirm.click();
  const confirmed = await pendingConfirm;
  expect(confirmed.ok(), await confirmed.text()).toBe(true);
  const replay = await page.request.post(confirmed.url(), { headers });
  expect(replay.ok()).toBe(true);
  expect(await listBatches()).toHaveLength(1);
  await expect(workflow.getByRole('status')).toHaveText('分镜图已就绪');
  await expect(workflow).toContainText('3/3 已出图');

  const before = (await (await page.request.get(`${path}/shots`)).json())[2];
  expect(before.images).toHaveLength(1);
  await send('把第 3 镜右边的灯改成红色，然后重新出图');
  const proposal = panel.getByRole('region', { name: '改镜提案' });
  await expect(proposal).toContainText('画面描述');
  await expect(proposal).toContainText('右边的灯为红色');
  expect(await (await page.request.get(`/api/shots/${before.id}`)).json()).toEqual(before);
  const pendingEdit = page.waitForResponse(response => response.url().endsWith('/apply') && response.request().method() === 'POST');
  await proposal.getByRole('button', { name: '保存并生成新候选' }).click();
  const edited = await pendingEdit;
  expect(edited.ok(), await edited.text()).toBe(true);
  const taskId = (await edited.json()).imageTaskId;
  expect(taskId).toBeTruthy();
  expect((await (await page.request.post(edited.url(), { headers })).json()).imageTaskId).toBe(taskId);
  await expect(proposal.getByText(/新候选已生成/)).toBeVisible();
  const after = await (await page.request.get(`/api/shots/${before.id}`)).json();
  expect(after.description).toContain('右边的灯为红色');
  expect(after.images).toHaveLength(2);
  expect(after.selectedImageId).toBe(before.selectedImageId);
});

test('Agent starts blank chats, restores history and keeps uploaded images private', async ({ page }, testInfo) => {
  await login(page);
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  const panel = page.getByRole('complementary', { name: 'Agent 对话' });
  const instruction = panel.getByLabel('Agent 指令');
  const send = panel.getByRole('button', { name: '发送', exact: true });
  await expect(panel.getByRole('heading', { name: '新会话' })).toBeVisible();
  await expect(panel.getByText('在下方输入消息开始对话')).toBeVisible();
  await expect(panel).not.toContainText('可用技能');
  await expect(send).toBeDisabled();
  await panel.screenshot({ path: testInfo.outputPath('agent-new-conversation.png') });

  const image = {
    name: '雨夜参考.png', mimeType: 'image/png',
    buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64'),
  };
  const input = panel.getByLabel('选择图片文件');
  await input.setInputFiles([{ ...image, name: '待移除.png' }, image]);
  await panel.getByRole('button', { name: '移除图片 待移除.png' }).click();
  await expect(panel.getByRole('img', { name: image.name })).toBeVisible();
  const pending = page.waitForResponse(response => response.url().endsWith('/messages') && response.request().method() === 'POST');
  await instruction.fill('雨夜构图参考');
  await instruction.press('Enter');
  const response = await pending;
  expect(response.ok(), await response.text()).toBe(true);
  const transcript = await response.json();
  const historyUrl = response.url().replace(/\/messages$/, '');
  const [saved] = transcript.messages;
  expect(saved.images).toHaveLength(1);
  const attachment = saved.images[0].url;
  await expect(panel.getByRole('status')).toContainText('图片已保存到此会话');
  await expect.poll(() => panel.getByRole('img', { name: image.name }).evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1);

  await panel.getByRole('button', { name: '新建会话' }).click();
  await expect(panel.getByRole('heading', { name: '新会话' })).toBeVisible();
  await expect(instruction).toBeEmpty();
  await expect(panel.getByRole('img', { name: image.name })).toHaveCount(0);
  await instruction.fill('你好');
  await send.click();
  await expect(panel.getByText(/暂时无法识别这条指令/)).toBeVisible();
  await expect(panel).not.toContainText('技能列表');
  await panel.getByRole('button', { name: '历史会话' }).click();
  await panel.getByRole('button', { name: /雨夜构图参考/ }).click();
  await expect(panel.getByRole('status')).toContainText('图片已保存到此会话');
  await page.reload();
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  await expect(panel.getByRole('heading', { name: '雨夜构图参考' })).toBeVisible();
  await expect(panel.getByRole('link', { name: `查看图片 ${image.name}` })).toHaveAttribute('href', attachment);

  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const headers = { [csrf.headerName]: csrf.token };
  const replayUrl = `/api/agent/conversations/${randomUUID()}/messages`;
  const multipart = { requestKey: randomUUID(), images: image };
  expect((await page.request.post(replayUrl, { headers, multipart })).ok()).toBe(true);
  const replay = await page.request.post(replayUrl, { headers, multipart });
  expect(replay.ok()).toBe(true);
  expect((await replay.json()).messages).toHaveLength(1);
  const corrupt = await page.request.post(replayUrl, { headers, multipart: {
    requestKey: randomUUID(), images: { ...image, buffer: Buffer.from('invalid image') },
  } });
  expect(corrupt.status()).toBe(400);

  await page.goto('/settings');
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  await login(page, process.env.WEB_TEST_OTHER_EMAIL!);
  expect((await page.request.get(historyUrl)).status()).toBe(404);
  expect((await page.request.get(attachment)).status()).toBe(404);
  expect(await (await page.request.get('/api/agent/conversations')).json()).toEqual([]);
});

test('logout removes cached user data and a second account cannot read the first account project', async ({ page }) => {
  await login(page);
  const projects = await (await page.request.get('/api/projects')).json();
  await page.goto('/settings'); await expect(page.getByText('后端实际配置', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '退出登录', exact: true }).click(); await expect(page).toHaveURL(/\/login$/);
  await login(page, process.env.WEB_TEST_OTHER_EMAIL!);
  await expect(page.getByText('浏览器联调验收', { exact: true })).toHaveCount(0);
  expect((await page.request.get(`/api/projects/${projects[0].id}`)).status()).toBe(404);
  expect((await (await page.request.get('/api/task-metrics')).json()).groups).toEqual([]);
});

test('initial backend unavailability visibly switches to isolated local demo mode', async ({ page }) => {
  await page.route('**/api/**', route => route.abort());
  await page.goto('/login'); await expect(page.getByText(/离线演示 · 数据仅保存在浏览器/)).toBeVisible();
  await page.getByRole('button', { name: '进入工作台', exact: true }).click();
  await expect(page).toHaveURL(/\/$/); await expect(page.getByText(/离线演示 · 数据仅保存在浏览器/)).toBeVisible();
});

test('custom style upload reaches the backend and removing its chooser entry preserves existing project style', async ({ page }) => {
  await login(page);
  await page.getByRole('button', { name: '新建项目', exact: true }).first().click();
  const dialog = page.getByRole('dialog', { name: '新建短剧项目' });
  await dialog.getByRole('tab', { name: /自定义/ }).click();
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');
  await dialog.locator('input[type=file]').setInputFiles({ name: '联调风格.png', mimeType: 'image/png', buffer: png });
  await expect(dialog.getByText('联调风格', { exact: true }).first()).toBeVisible();
  await dialog.getByPlaceholder('例如：长夜将明').fill('自定义风格验收');
  await dialog.getByRole('button', { name: '创建项目', exact: true }).click();
  await expect(page).toHaveURL(/\/projects\/\d+$/);
  const projectPath = new URL(page.url()).pathname;
  await expect(page.getByText('联调风格', { exact: true })).toBeVisible();
  await page.goto('/');
  await page.getByRole('button', { name: '新建项目', exact: true }).first().click();
  await dialog.getByRole('tab', { name: /自定义/ }).click();
  const remove = dialog.getByRole('button', { name: '删除自定义风格 联调风格' });
  await dialog.getByRole('radio', { name: /联调风格/ }).hover(); await remove.click();
  await expect(remove).toHaveCount(0);
  await page.goto(projectPath);
  await expect(page.getByText('联调风格', { exact: true })).toBeVisible();
});

test('team invitation, shared workspace, read-only downgrade and revocation', async ({ page, browser, baseURL }) => {
  await login(page);
  const otherContext = await browser.newContext({ baseURL });
  try {
    await otherContext.route('**/*', route => {
      const url = route.request().url();
      return url.startsWith(baseURL + '/') || url.startsWith('data:') || url.startsWith('blob:')
        ? route.continue() : route.abort('blockedbyclient');
    });
    const other = await otherContext.newPage();
    const email = process.env.WEB_TEST_OTHER_EMAIL!;
    await login(other, email);

    await page.getByRole('link', { name: '团队管理', exact: true }).click();
    await page.getByLabel('团队名称', { exact: true }).fill('团队协作验收');
    await page.getByRole('button', { name: '创建团队', exact: true }).click();
    await expect(page.getByRole('heading', { name: '团队协作验收', exact: true })).toBeVisible();
    const teamPath = new URL(page.url()).pathname;
    await page.getByLabel('账号邮箱').fill(email);
    await page.getByRole('button', { name: '发出站内邀请' }).click();
    await expect(page.getByText(email + ' · 成员', { exact: false })).toBeVisible();

    await other.getByRole('link', { name: '团队管理', exact: true }).click();
    await other.getByRole('button', { name: '接受邀请' }).click();
    await expect(other).toHaveURL(new RegExp(teamPath + '$'));
    await other.getByRole('button', { name: '项目', exact: true }).click();
    await other.getByRole('button', { name: '新建团队项目', exact: true }).click();
    await other.getByLabel('项目名称', { exact: true }).fill('共享短剧');
    await other.getByRole('button', { name: '创建项目', exact: true }).click();
    await other.getByRole('button', { name: /共享短剧/ }).click();
    await expect(other.getByText('团队协作验收', { exact: true })).toBeVisible();
    await other.getByRole('button', { name: '新建一集', exact: true }).first().click();

    const story = '夜雨中，沈夜收起伞走进便利店。店员听见门铃抬头，两人隔着玻璃看向空旷街道。';
    const storyField = other.getByPlaceholder(/把这一集的剧本贴在这里/);
    await storyField.fill(story);
    await other.getByRole('button', { name: 'AI 拆解镜头', exact: true }).click();
    await expect(other.getByRole('button', { name: '重新拆解', exact: true })).toBeVisible();
    const storyPath = new URL(other.url()).pathname;
    const episodeId = storyPath.split('/')[4];
    await other.getByRole('link', { name: '分镜', exact: true }).click();
    await other.getByRole('button', { name: '全部确认', exact: true }).first().click();
    const row = other.locator('tbody tr').first();
    await row.getByRole('button', { name: '生成分镜图', exact: true }).click();
    await expect(row.locator('img').first()).toBeVisible();
    const shots = await (await other.request.get('/api/episodes/' + episodeId + '/shots')).json();
    const imageUrl = shots[0].images[0].url;
    await other.getByRole('link', { name: '视频', exact: true }).click();
    await other.getByRole('button', { name: '生成视频', exact: true }).first().click();
    await expect.poll(async () => (await (await other.request.get('/api/shots/' + shots[0].id)).json()).video.ready).toBe(true);
    const videoUrl = (await (await other.request.get('/api/shots/' + shots[0].id)).json()).video.url;
    await other.goto(storyPath);

    await page.goto(storyPath);
    await expect(page.getByPlaceholder(/把这一集的剧本贴在这里/)).toHaveValue(story);
    expect((await page.request.get(imageUrl)).status()).toBe(200);
    expect((await page.request.get(videoUrl, { headers: { Range: 'bytes=0-127' } })).status()).toBe(206);

    await page.goto(teamPath);
    const memberRow = page.getByRole('row').filter({ hasText: email });
    page.once('dialog', dialog => dialog.accept());
    await memberRow.getByRole('combobox').selectOption('VIEWER');
    await expect(storyField).toHaveAttribute('readonly', '');
    await expect(other.getByRole('button', { name: '重新拆解', exact: true })).toBeDisabled();
    expect((await other.request.get(videoUrl, { headers: { Range: 'bytes=0-127' } })).status()).toBe(206);

    page.once('dialog', dialog => dialog.accept());
    await memberRow.getByRole('button', { name: '移除', exact: true }).click();
    await expect(other).toHaveURL(/\/teams$/);
    expect((await other.request.get(imageUrl)).status()).toBe(404);
    expect((await other.request.get(videoUrl, { headers: { Range: 'bytes=0-127' } })).status()).toBe(404);
    await expect(memberRow).toHaveCount(0);
  } finally {
    await otherContext.close();
  }
});

test('episode workflow hands page batches back to Agent and preserves video first frames', async ({ page }) => {
  await login(page);
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const headers = { [csrf.headerName]: csrf.token };
  const project = await page.request.post('/api/projects', { headers, data: { name: '阶段十二协同验收', visualType: 'realistic' } });
  expect(project.status()).toBe(201);
  const projectId = (await project.json()).id;
  const episode = await page.request.post(`/api/projects/${projectId}/episodes`, { headers, data: {} });
  expect(episode.status()).toBe(201);
  const episodeId = (await episode.json()).id;
  const path = `/api/episodes/${episodeId}`;
  const story = '沈夜走进雨夜街道，低头看见积水。\n便利店的门打开，店员望向街边。\n沈夜停在右边的白色路灯下。';
  expect((await page.request.put(`${path}/story`, { headers, data: { story } })).ok()).toBe(true);

  await page.goto(`/projects/${projectId}/episodes/${episodeId}/story`);
  const open = () => page.getByRole('button', { name: 'Agent', exact: true }).click();
  await open();
  const panel = page.getByRole('complementary', { name: 'Agent 对话' });
  const run = panel.getByRole('region', { name: '拆镜与出图流程' });
  const send = async (instruction: string) => {
    const reply = page.waitForResponse(response => response.url().endsWith('/messages') && response.request().method() === 'POST');
    await panel.getByLabel('Agent 指令').fill(instruction);
    await panel.getByRole('button', { name: '发送', exact: true }).click();
    const response = await reply;
    expect(response.ok(), await response.text()).toBe(true);
    return response.json();
  };

  await expect(panel.getByRole('region', { name: '本集工作流' })).toContainText('正文已就绪');
  await send('把这集的故事拆成镜头，我确认后给没图的镜头出图');
  await expect(run.getByRole('button', { name: '确认镜头并出图' })).toBeVisible();
  await page.getByRole('link', { name: '分镜', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/episodes/${episodeId}/storyboard$`));
  await panel.getByRole('button', { name: '收起 Agent' }).click();
  const confirmAll = page.getByTitle('把这一集所有待确认镜头标记为已确认');
  await confirmAll.click();
  await expect(confirmAll).toBeDisabled();
  await page.getByRole('button', { name: '本集批量生成分镜图', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '本集批量生成分镜图' });
  const batchResponse = page.waitForResponse(response => response.url().endsWith('/generation-batches') && response.request().method() === 'POST');
  await dialog.getByRole('button', { name: '提交 3 镜' }).click();
  const batch = await (await batchResponse).json();
  await expect(dialog.getByText(`批次 #${batch.id}`, { exact: false })).toBeVisible();
  await dialog.getByRole('button', { name: '关闭' }).click();

  await page.reload();
  await open();
  const continued = await send('继续做这集');
  expect(continued.messages[0].workflow.batches.map((item: { id: string }) => item.id)).toEqual([batch.id]);
  expect((await (await page.request.get(`${path}/generation-batches?type=IMAGE`)).json()).map((item: { id: string }) => item.id)).toEqual([batch.id]);
  await expect(panel.getByRole('region', { name: '本集工作流' })).toContainText('请检查已选首帧');

  await panel.getByRole('button', { name: '新建会话' }).click();
  const standalone = await send('继续');
  expect(standalone.messages[0].result.message).toContain('请检查已选首帧');
  expect(await (await page.request.get(`${path}/generation-batches?type=VIDEO`)).json()).toEqual([]);
  const video = await send('给本集已选首帧且缺视频的镜头生成视频');
  expect(video.messages[1].result.batchId).toBeTruthy();
  await expect.poll(async () => {
    const shots = await (await page.request.get(`${path}/shots`)).json();
    return shots.map((shot: { video: { ready: boolean; basedOn: string }; selectedImageId: string }) =>
      shot.video.ready && shot.video.basedOn === shot.selectedImageId);
  }).toEqual([true, true, true]);
  await expect(panel.getByRole('region', { name: '本集工作流' })).toContainText('本集镜头均已有视频');
});
