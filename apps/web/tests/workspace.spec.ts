import { test, expect, type Page } from '@playwright/test';
import { isAgentReply, readAgentReply } from './agent-reply';
import { randomUUID } from 'node:crypto';
import { prepareReferences, referencePng } from './reference-fixture';

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
  await expect(page).toHaveURL(/\/$/);
}

async function openBlankEpisode(page: Page) {
  await expect(page).toHaveURL(/\/projects\/\d+$/);
  const projectPath = new URL(page.url()).pathname;
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const created = await page.request.post(`/api${projectPath}/episodes`, {
    headers: { [csrf.headerName]: csrf.token }, data: {},
  });
  expect(created.status()).toBe(201);
  const { id } = await created.json();
  await page.goto(`${projectPath}/episodes/${id}/story`);
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
  await expect(page.getByText('后端已连接', { exact: true })).toHaveCount(0);
  const projectId = new URL(page.url()).pathname.split('/')[2];
  const overview = page.getByRole('main');
  await overview.getByRole('button', { name: '精品短剧', exact: true }).click();
  const currentStyle = overview.getByRole('button', { name: '日系赛璐璐', exact: true });
  const styleDialog = page.getByRole('dialog', { name: '切换项目风格' });
  await styleDialog.getByRole('tab', { name: /漫剧/ }).click();
  await styleDialog.getByRole('radio', { name: /日系赛璐璐/ }).click();
  await styleDialog.getByLabel('风格描述（可选）').fill('低饱和、雨夜反光');
  await styleDialog.getByRole('button', { name: '保存风格' }).click();
  await expect(styleDialog).toHaveCount(0);
  await page.reload();
  await expect(currentStyle).toBeVisible();
  await expect(page.getByRole('main').getByText('低饱和、雨夜反光', { exact: true })).toBeVisible();
  const agent = page.getByRole('complementary', { name: 'Agent 对话' });
  const draft = agent.getByLabel('Agent 指令');
  await expect(agent).toBeVisible();
  await expect(page.getByText(/人物与场景是可跨集复用的资产/)).toHaveCount(0);
  await draft.fill('下一步创作草稿');
  await page.setViewportSize({ width: 1100, height: 900 });
  await page.getByRole('button', { name: '收起 Agent' }).click();
  await expect(agent).toHaveCount(0);
  const chatIcon = page.getByRole('button', { name: '打开 Agent 对话' });
  await expect(chatIcon).toHaveText('');
  await chatIcon.click();
  await expect(draft).toHaveValue('下一步创作草稿');
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openBlankEpisode(page);
  await expect(page).toHaveURL(/\/episodes\/\d+\/story$/);
  const episodeId = new URL(page.url()).pathname.split('/')[4];
  const story = '雨夜街道，沈夜收起伞走向便利店。店员抬起头，窗边的路灯把积水照得发亮。\n\n许星遥放下牛奶，低声说：「你终于回来了。」沈夜站在门口，看着玻璃窗上滑落的雨水。';
  const textarea = page.getByPlaceholder(/把这一集的剧本贴在这里/);
  await expect(page.locator('main input[type=file]')).toHaveCount(0);
  await textarea.fill(story);
  // Click immediately: the action must flush the debounced story write before submitting breakdown.
  await page.getByRole('button', { name: 'AI 拆解镜头', exact: true }).click();
  await page.getByRole('link', { name: '分镜', exact: true }).click();
  await expect(page.getByRole('button', { name: '重新拆解', exact: true })).toBeVisible();
  const row = page.locator('tbody tr').first();
  const generate = row.getByRole('button', { name: '生成分镜图', exact: true });
  await expect(generate).toBeDisabled();
  await generate.press('Enter');
  await expect(row.getByRole('status')).toContainText(/请先为场景.+生成或上传参考图/);
  await expect(row.getByRole('button', { name: '确认画面', exact: true })).toHaveCount(0);
  await row.getByRole('button', { name: /^编辑第 01 镜：/ }).click();
  await page.getByLabel('画面描述', { exact: true }).fill('浏览器人工编辑：沈夜在便利店门前停步。');
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await expect(row).toContainText('浏览器人工编辑');
  await expect(generate).toBeDisabled();

  // Upload a real file through the page, then freeze that asset version in the next image task.
  await page.goto(`/projects/${projectId}/scenes`);
  const assetRow = page.locator('article').first();
  await assetRow.locator('input[type=file]').setInputFiles({ name: '场景参考.png', mimeType: 'image/png', buffer: referencePng });
  await expect(assetRow).toContainText('1 个版本');
  await page.goto(`/projects/${projectId}/episodes/${episodeId}/storyboard`);
  const mainFrame = row.getByRole('button', { name: '预览第 01 镜分镜图' });
  const references = row.getByRole('list', { name: '当前参考素材' });
  const reference = references.getByRole('button', { name: /^查看.*基准图$/ });
  const referenceImage = references.getByRole('img', { name: /基准图$/ });
  await expect(reference.locator('img')).toBeVisible();
  await expect(referenceImage).toBeHidden();
  await reference.click();
  await expect(referenceImage).toBeVisible();
  await expect(referenceImage).toHaveAttribute('src', /^\/api\/asset-versions\//);
  await page.keyboard.press('Escape');
  await expect(referenceImage).toBeHidden();
  await row.getByRole('button', { name: '生成分镜图', exact: true }).click();
  await expect(mainFrame.locator('img')).toBeVisible();
  const shots = await (await page.request.get(`/api/episodes/${episodeId}/shots`)).json();
  await page.goto(`/projects/${projectId}`);
  await expect(page.getByRole('heading', { name: '浏览器联调验收' })).toBeVisible();
  await expect(overview.getByText('日系赛璐璐', { exact: true })).toBeVisible();
  await expect(currentStyle).toHaveCount(0);
  await page.goto(`/projects/${projectId}/episodes/${episodeId}/storyboard`);
  const shotId = shots[0].id, firstImage = shots[0].selectedImageId;
  const readShot = async () => (await page.request.get(`/api/shots/${shotId}`)).json();
  const preview = page.getByRole('dialog', { name: /分镜预览/ });
  const confirmFrame = row.getByRole('button', { name: '确认画面', exact: true });
  await confirmFrame.click();
  await expect(row.getByText('已确认', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: '取消确认', exact: true }).click();
  await expect(row.getByText('待确认', { exact: true })).toBeVisible();
  expect((await readShot()).selectedImageId).toBe(firstImage);
  expect(shots[0].images[0].inputSnapshot.scene.versionId).toBeTruthy();
  await row.getByRole('button', { name: '再生成一版', exact: true }).click();
  const regeneration = page.getByRole('dialog', { name: '再生成一版' });
  const revisedDescription = '沈夜停在便利店门口，门内的灯照在积水上。';
  await expect(regeneration.getByLabel('画面描述', { exact: true })).toHaveValue(shots[0].description);
  await regeneration.getByLabel('画面描述', { exact: true }).fill(revisedDescription);
  await regeneration.getByRole('button', { name: '生成新候选', exact: true }).click();
  await expect(regeneration).toHaveCount(0);
  await expect.poll(async () => (await (await page.request.get(`/api/shots/${shotId}`)).json()).images.length).toBe(2);
  const creations = (await (await page.request.get(`/api/image-experience/targets/SHOT/${shotId}`)).json()).creations;
  expect(creations[0].prompt).toContain(revisedDescription);
  expect(creations[0].prompt).toContain('日系赛璐璐');
  expect(creations[0].prompt).toContain('低饱和、雨夜反光');
  expect((await readShot()).description).toBe(revisedDescription);
  const nextImage = (await readShot()).images[1].id;
  const candidate = row.getByRole('button', { name: /查看 候选 2$/ });
  await candidate.click();
  await expect(mainFrame.locator('img')).toHaveAttribute('src', `/api/shot-images/${nextImage}/image`);
  await expect(candidate).toHaveAttribute('aria-current', 'true');
  await expect(preview).toHaveCount(0);
  expect(await readShot()).toMatchObject({ selectedImageId: firstImage, status: 'draft' });
  await mainFrame.click();
  await expect(preview.getByRole('img', { name: '第 01 镜 · 候选 2' })).toHaveAttribute('src', `/api/shot-images/${nextImage}/image`);
  await preview.getByRole('button', { name: '关闭', exact: true }).click();
  await confirmFrame.click();
  await expect(row.getByText('已确认', { exact: true })).toBeVisible();
  const selected = await readShot();
  expect(selected).toMatchObject({ selectedImageId: nextImage, status: 'confirmed' });
  await page.getByRole('link', { name: '视频', exact: true }).click();
  await page.getByRole('button', { name: '生成视频', exact: true }).first().click();
  await expect.poll(async () => (await (await page.request.get(`/api/shots/${shotId}`)).json()).video.taskId).toBeTruthy();
  await page.goto(`/projects/${projectId}/episodes/${episodeId}/storyboard`);
  await row.getByRole('button', { name: /查看 候选 1$/ }).click();
  await confirmFrame.click();
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
  const videoCard = page.locator('article').first();
  const video = videoCard.getByLabel('镜头视频', { exact: true });
  await expect(video).toBeVisible();
  await expect(video).toHaveAttribute('src', /\/api\/tasks\/\d+\/video/);
  await expect(video).toHaveAttribute('poster', `/api/shot-images/${selected.selectedImageId}/image`);
  await expect(video).toHaveAttribute('controls', '');
  await expect(video).toHaveAttribute('playsinline', '');
  await video.evaluate((element: HTMLVideoElement) => element.play());
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime)).toBeGreaterThan(0);
  expect(page.context().pages()).toHaveLength(1);
  await videoCard.getByText('镜头详情与首帧来源', { exact: true }).click();
  await expect(videoCard.getByText(/首帧来源 · 候选 2/)).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('connected-video.png'), fullPage: true });
  await page.reload(); await expect(video).toBeVisible();
  const regenerateVideo = videoCard.getByRole('button', { name: '重新生成', exact: true });
  const videoSettings = videoCard.getByRole('form', { name: '重新生成视频' });
  let videoSubmissions = 0;
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith(`/api/shots/${shotId}/video-tasks`)) videoSubmissions++;
  });
  await regenerateVideo.click();
  await expect(videoSettings.getByRole('img', { name: '本次生成首帧' })).toHaveAttribute('src', `/api/shot-images/${firstImage}/image`);
  await expect(videoSettings.getByLabel('视频提示词')).toHaveValue(ready.video.prompt);
  await videoSettings.getByRole('button', { name: '取消', exact: true }).click();
  expect(videoSubmissions).toBe(0);
  expect((await readShot()).video.taskId).toBe(ready.video.taskId);
  await regenerateVideo.click();
  const videoPrompt = '沈夜在门前缓缓回头，镜头从右向左平移。';
  await videoSettings.getByLabel('视频提示词').fill(videoPrompt);
  await videoSettings.getByLabel('景别').selectOption('近景');
  await videoSettings.getByLabel('运镜').selectOption('移');
  await videoSettings.getByRole('button', { name: '确认生成' }).click();
  await expect(videoSettings).toHaveCount(0);
  await expect.poll(async () => (await readShot()).video.prompt).toBe(`${videoPrompt}；景别：近景；运镜：移`);
  expect(videoSubmissions).toBe(1);
  expect(await readShot()).toMatchObject({ description: revisedDescription, video: { basedOn: firstImage, ready: true } });
  await expect(video).toHaveAttribute('poster', `/api/shot-images/${firstImage}/image`);
  await regenerateVideo.click();
  await expect(videoSettings.getByLabel('视频提示词')).toHaveValue(videoPrompt);
  await expect(videoSettings.getByLabel('景别')).toHaveValue('近景');
  await expect(videoSettings.getByLabel('运镜')).toHaveValue('移');
  // Generate again without changing the storyboard frame. Both results must remain independently playable.
  const nextVideoPrompt = '沈夜站定，镜头沿门框缓缓推近。';
  await videoSettings.getByLabel('视频提示词').fill(nextVideoPrompt);
  await videoSettings.getByRole('button', { name: '确认生成' }).click();
  await expect.poll(async () => (await readShot()).video.candidates.length).toBe(3);
  const [latest, sameFrame, otherFrame] = (await readShot()).video.candidates;
  expect(latest.basedOn).toBe(firstImage);
  expect(sameFrame.basedOn).toBe(firstImage);
  expect(otherFrame.basedOn).toBe(nextImage);
  expect(new Set([latest.url, sameFrame.url, otherFrame.url]).size).toBe(3);

  await page.reload();
  const candidates = videoCard.getByRole('region', { name: '视频候选' });
  await expect(candidates).toContainText('共 3 版');
  await expect(video).toHaveAttribute('src', latest.url);
  await expect(candidates.getByRole('button', { name: /^第 3 版 / })).toHaveAttribute('aria-pressed', 'true');
  await candidates.getByRole('button', { name: /^第 2 版 / }).click();
  await expect(video).toHaveAttribute('src', sameFrame.url);
  await expect(video).toHaveAttribute('poster', `/api/shot-images/${firstImage}/image`);
  await video.evaluate((element: HTMLVideoElement) => element.play());
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime)).toBeGreaterThan(0);
  await videoCard.getByText('镜头详情与首帧来源', { exact: true }).click();
  await expect(videoCard.getByText(`视频提示词 · ${sameFrame.prompt}`, { exact: true })).toBeVisible();
  await candidates.getByRole('button', { name: /^第 1 版 / }).click();
  await expect(video).toHaveAttribute('src', otherFrame.url);
  await expect(video).toHaveAttribute('poster', `/api/shot-images/${nextImage}/image`);
  await expect(videoCard.getByText(/首帧来源 · 候选 2/)).toBeVisible();
  expect((await readShot()).selectedImageId).toBe(firstImage);
  expect(videoSubmissions).toBe(2);
  await page.screenshot({ path: testInfo.outputPath('video-candidates.png'), fullPage: true });

  await page.getByRole('link', { name: '分镜', exact: true }).click();
  const deleteButton = row.getByRole('button', { name: '删除候选', exact: true });
  const deleteDialog = page.getByRole('dialog', { name: '删除候选图？' });
  await candidate.click();
  await deleteButton.click();
  await expect(deleteDialog.getByRole('img', { name: '候选 2' })).toBeVisible();
  await deleteDialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(candidate).toBeVisible();
  await deleteButton.click();
  await deleteDialog.getByRole('button', { name: '确认删除', exact: true }).click();
  await expect(candidate).toHaveCount(0);
  await expect(row.getByText('已确认', { exact: true })).toBeVisible();
  await deleteButton.click();
  await deleteDialog.getByRole('button', { name: '确认删除', exact: true }).click();
  await expect(row.getByText('待出图', { exact: true })).toBeVisible();
  await page.reload();
  await expect(row.getByText('待出图', { exact: true })).toBeVisible();
  const emptied = await readShot();
  expect(emptied).toMatchObject({ images: [], status: 'draft' });
  expect(emptied.selectedImageId).toBeUndefined();
  await page.getByRole('link', { name: '视频', exact: true }).click();
  await expect(video).toHaveAttribute('src', latest.url);
  await candidates.getByRole('button', { name: /^第 1 版 / }).click();
  await expect(video).toHaveAttribute('src', otherFrame.url);
  await videoCard.getByText('镜头详情与首帧来源', { exact: true }).click();
  const historical = videoCard.getByAltText('历史首帧');
  await expect(historical).toHaveAttribute('src', `/api/shot-images/${nextImage}/image`);
  await expect.poll(() => historical.evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(0);

  await page.goto(`/projects/${projectId}/episodes/${episodeId}/story`); await expect(textarea).toHaveValue(story);
  await expect(page.getByRole('button', { name: /^(AI 拆解镜头|重新拆解)$/ })).toHaveCount(0);
  await expect(page.getByRole('region', { name: '本集工作流' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^(批量确认画面|其他分集)$/ })).toHaveCount(0);
  await expect(page.getByRole('region', { name: '本集创作会话' })).toHaveCount(0);
  await expect(page.getByText('集进度', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('集名', { exact: true })).toHaveCount(0);
  await page.getByRole('link', { name: '分镜', exact: true }).click();
  await page.getByRole('button', { name: '重新拆解', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '重新拆解这一集？' });
  await expect(dialog).toContainText('分镜图和视频');
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  const unchanged = await (await page.request.get(`/api/shots/${shotId}`)).json();
  expect(unchanged.video.ready).toBe(true); expect(unchanged.video.basedOn).toBe(firstImage);
  await page.getByRole('button', { name: '重新拆解', exact: true }).click();
  const submitted = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/breakdowns'));
  await dialog.getByRole('button', { name: '确认重新拆解', exact: true }).click();
  const replacement = await submitted;
  expect(replacement.status()).toBe(202);
  expect(replacement.request().postDataJSON()).toEqual({ replaceExisting: true });
  await expect.poll(async () => {
    const currentShots = await (await page.request.get(`/api/episodes/${episodeId}/shots`)).json();
    return currentShots.length > 0 && currentShots.every((shot: { id: string; status: string }) =>
      shot.id !== shotId && shot.status === 'draft');
  }).toBe(true);
  expect(await page.evaluate(() => localStorage.getItem('vibestory.state.v2'))).toBeNull();
  expect(errors).toEqual([]);
});

test('save failure keeps the draft and prevents generation until it is saved', async ({ page }) => {
  await login(page);
  await page.getByRole('button', { name: '新建项目', exact: true }).first().click();
  await page.getByPlaceholder('例如：长夜将明').fill('拆解保存失败验收');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await openBlankEpisode(page);
  const field = page.getByPlaceholder(/把这一集的剧本贴在这里/);
  await field.fill('雨夜街道，沈夜收起伞走向便利店。店员抬起头，窗边的路灯把积水照得发亮。');
  await page.getByRole('button', { name: 'AI 拆解镜头', exact: true }).click();
  await expect(page.getByRole('link', { name: '分镜', exact: true })).toBeVisible();
  let fail = true;
  let breakdownSubmissions = 0;
  await page.route('**/api/episodes/*/breakdowns', route => {
    if (route.request().method() === 'POST') breakdownSubmissions++;
    return route.continue();
  });
  await page.route('**/api/episodes/*/story', async route => {
    if (route.request().method() === 'PUT' && fail) return route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ detail: '验收：保存暂不可用' }) });
    return route.continue();
  });
  const draft = '保存失败仍需保留的人工草稿，不能被后台轮询覆盖。';
  await field.fill(draft); await expect(page.getByRole('alert')).toContainText('保存暂不可用'); await expect(field).toHaveValue(draft);
  await page.getByRole('link', { name: '分镜', exact: true }).click();
  await page.getByRole('button', { name: '重新拆解', exact: true }).click();
  const failedSave = page.waitForResponse(response => response.request().method() === 'PUT' && response.url().endsWith('/story'));
  await page.getByRole('button', { name: '确认重新拆解', exact: true }).click();
  expect((await failedSave).status()).toBe(503);
  await page.getByRole('link', { name: '故事', exact: true }).click();
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
  const panel = page.getByRole('complementary', { name: 'Agent 对话' });
  const field = panel.getByLabel('Agent 指令');
  async function send(instruction: string) {
    const pending = page.waitForResponse(isAgentReply, { timeout: 15000 });
    await field.fill(instruction);
    await panel.getByRole('button', { name: '发送', exact: true }).click();
    const response = await pending;
    expect(response.ok(), await response.text()).toBe(true);
    const result = (await readAgentReply(response)).transcript.messages!.at(-1)!.result!;
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
  const scenes = await (await page.request.get(`/api/projects/${projectId}/scenes`)).json();
  const scene = scenes[0];
  const asset = await send(`给${scene.name}生成一版场景图`);
  await expect.poll(async () => (await (await page.request.get(`/api/tasks/${asset.result.taskId}`)).json()).ready).toBe(true);
  const selection = await send(`把场景《${scene.name}》的第 1 版设为基准`);
  expect(selection.result.message).toContain('设为基准');

  const batch = await send('给没图的镜头出图');
  expect(batch.result.batchId).toBeTruthy();
  await expect.poll(async () => (await (await page.request.get(`/api/generation-batches/${batch.result.batchId}`)).json()).progress.succeeded).toBe(2);
  await send('确认全部镜头');
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

test('Agent restores the breakdown workflow and generates candidates before shot confirmation', async ({ page }) => {
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
  const panel = page.getByRole('complementary', { name: 'Agent 对话' });
  const workflow = panel.getByRole('region', { name: '拆镜与出图流程' });
  const send = async (instruction: string) => {
    await panel.getByLabel('Agent 指令').fill(instruction);
    await panel.getByRole('button', { name: '发送', exact: true }).click();
  };
  await send('把这集的故事拆成镜头，准备生成分镜图');
  const confirm = workflow.getByRole('button', { name: '生成缺失分镜图' });
  await expect(confirm).toBeVisible();
  const listBatches = async () => (await page.request.get(`${path}/generation-batches?type=IMAGE`)).json();
  expect(await listBatches()).toEqual([]);

  await page.reload();
  const pendingConfirm = page.waitForResponse(response => response.url().includes('/workflows/') && response.url().endsWith('/confirm'));
  await confirm.click();
  const confirmed = await pendingConfirm;
  expect(confirmed.ok(), await confirmed.text()).toBe(true);
  const replay = await page.request.post(confirmed.url(), { headers });
  expect(replay.ok()).toBe(true);
  expect(await listBatches()).toHaveLength(1);
  await expect(workflow.getByRole('status')).toHaveText(/分镜图已就绪/);
  await expect(workflow).toContainText('3/3 已出图');

  const before = (await (await page.request.get(`${path}/shots`)).json())[2];
  expect(before.images).toHaveLength(1);
  expect(before.status).toBe('draft');
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
  await page.getByRole('button', { name: '打开 Agent 对话', exact: true }).click();
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
  const pending = page.waitForResponse(isAgentReply);
  await instruction.fill('雨夜构图参考');
  await instruction.press('Enter');
  const response = await pending;
  expect(response.ok(), await response.text()).toBe(true);
  const { transcript } = await readAgentReply(response);
  const historyUrl = response.url().replace(/\/messages\/stream$/, '');
  const [saved] = transcript.messages!;
  expect(saved.images).toHaveLength(1);
  const attachment = saved.images![0].url!;
  await expect(panel.getByRole('status')).toContainText('图片已保存到此会话');
  await expect.poll(() => panel.getByRole('img', { name: image.name }).evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1);

  await panel.getByRole('button', { name: '新建会话' }).click();
  await expect(panel.getByRole('heading', { name: '新会话' })).toBeVisible();
  await expect(instruction).toBeEmpty();
  await expect(panel.getByRole('img', { name: image.name })).toHaveCount(0);
  await instruction.fill('你好');
  const streamed = page.waitForResponse(isAgentReply);
  await send.click();
  const partial = panel.getByTestId('agent-streaming-reply');
  await expect(partial).toBeVisible();
  await expect(partial).not.toHaveText('暂时无法识别这条指令，可以输入 / 查看可用操作。');
  const { events } = await readAgentReply(await streamed);
  expect(events[0].type).toBe('start');
  expect(events.filter(event => event.type === 'delta').length).toBeGreaterThan(1);
  await expect(panel.getByText(/暂时无法识别这条指令/)).toBeVisible();
  await expect(panel).not.toContainText('技能列表');
  await panel.getByRole('button', { name: '历史会话' }).click();
  await panel.getByRole('button', { name: /雨夜构图参考/ }).click();
  await expect(panel.getByRole('status')).toContainText('图片已保存到此会话');
  await page.reload();
  await page.getByRole('button', { name: '打开 Agent 对话', exact: true }).click();
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

test('Agent history shows episode numbers without covering the current conversation', async ({ page }) => {
  await login(page);
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const headers = { [csrf.headerName]: csrf.token };
  const createProject = async (name: string) => {
    const response = await page.request.post('/api/projects', { headers, data: { name, visualType: 'realistic' } });
    expect(response.ok(), await response.text()).toBe(true);
    return (await response.json()).id;
  };
  const projectId = await createProject('历史会话验收');
  const otherProjectId = await createProject('另一个项目');
  const createEpisode = async () => {
    const response = await page.request.post(`/api/projects/${projectId}/episodes`, { headers, data: {} });
    expect(response.ok(), await response.text()).toBe(true);
    return (await response.json()).id;
  };
  const first = await createEpisode();
  const second = await createEpisode();
  const firstMain = await page.request.post(`/api/episodes/${first}/creative/session`, { headers });
  expect(firstMain.ok(), await firstMain.text()).toBe(true);
  const seedChat = async (instruction: string, projectId: string, episodeId?: string) => {
    const response = await page.request.post(`/api/agent/conversations/${randomUUID()}/messages`, { headers, multipart: {
      requestKey: randomUUID(), instruction, projectId, ...(episodeId ? { episodeId } : {}),
    } });
    expect(response.ok(), await response.text()).toBe(true);
  };
  await seedChat('分镜构图讨论', projectId, first);
  await seedChat('另一个项目的对话', otherProjectId);

  await page.goto(`/projects/${projectId}/episodes/${second}/story`);
  const panel = page.getByRole('complementary', { name: 'Agent 对话' });
  const composer = panel.getByLabel('Agent 指令');
  const toggle = panel.getByRole('button', { name: '历史会话' });
  const history = panel.getByRole('region', { name: '历史会话' });
  const longMessage = `第二集正在讨论的内容\n${'构图、角色和场景。\n'.repeat(23)}讨论到这里。`;
  await composer.fill(longMessage);
  await panel.getByRole('button', { name: '发送', exact: true }).click();
  const latestReply = panel.getByRole('status').filter({ hasText: '暂时无法识别这条指令' });
  await expect(latestReply).toBeInViewport();
  await composer.fill('尚未发送的草稿');
  await toggle.click();
  await expect(history.getByRole('button', { name: /第 1 集 · 主会话/ })).toBeVisible();
  await expect(history.getByRole('button', { name: /第 2 集 · 主会话/ })).toHaveAttribute('aria-current', 'true');
  await expect(history).not.toContainText('另一个项目的对话');
  await expect(latestReply).toBeInViewport();
  await expect(composer).toBeInViewport();
  await expect(composer).toHaveValue('尚未发送的草稿');
  await toggle.click();
  await expect(history).toBeHidden();
  await expect(composer).toHaveValue('尚未发送的草稿');

  await toggle.click();
  await history.getByRole('button', { name: /第 1 集 · 分镜构图讨论/ }).click();
  await expect(page).toHaveURL(`/projects/${projectId}/episodes/${first}/story`);
  await expect(panel.getByRole('heading', { name: '分镜构图讨论' })).toBeVisible();
  await expect(history).toBeHidden();
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
  await expect(page.getByRole('button', { name: '注册账号', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '进入工作台', exact: true }).click();
  await expect(page).toHaveURL(/\/$/); await expect(page.getByText(/离线演示 · 数据仅保存在浏览器/)).toBeVisible();
  await page.getByRole('button', { name: '新建项目', exact: true }).first().click();
  await page.getByPlaceholder('例如：长夜将明').fill('离线换风格');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('main').getByRole('button', { name: '精品短剧', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '切换项目风格' });
  await dialog.getByRole('radio', { name: /精品古装/ }).click();
  await dialog.getByRole('button', { name: '保存风格' }).click();
  await expect(dialog).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole('main').getByText('精品古装', { exact: true })).toBeVisible();
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
    await expect(other.getByRole('link', { name: '分镜', exact: true })).toBeVisible();
    const storyPath = new URL(other.url()).pathname;
    const episodeId = storyPath.split('/')[4];
    await other.getByRole('link', { name: '分镜', exact: true }).click();
    await expect(other.locator('tbody tr').first()).toBeVisible();
    await prepareReferences(other, storyPath.split('/')[2]);
    await other.reload();
    const row = other.locator('tbody tr').first();
    await row.getByRole('button', { name: '生成分镜图', exact: true }).click();
    await expect(row.getByRole('button', { name: '预览第 01 镜分镜图' }).locator('img')).toBeVisible();
    await row.getByRole('button', { name: '确认画面', exact: true }).click();
    await expect(row.getByText('已确认', { exact: true })).toBeVisible();
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
    await other.getByRole('link', { name: '分镜', exact: true }).click();
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
  const panel = page.getByRole('complementary', { name: 'Agent 对话' });
  const run = panel.getByRole('region', { name: '拆镜与出图流程' });
  const send = async (instruction: string) => {
    const reply = page.waitForResponse(isAgentReply);
    await panel.getByLabel('Agent 指令').fill(instruction);
    await panel.getByRole('button', { name: '发送', exact: true }).click();
    const response = await reply;
    expect(response.ok(), await response.text()).toBe(true);
    return (await readAgentReply(response)).transcript;
  };

  await expect(panel.getByRole('region', { name: '本集工作流' })).toHaveCount(0);
  await send('把这集的故事拆成镜头，准备生成分镜图');
  await expect(run.getByRole('button', { name: '生成缺失分镜图' })).toBeVisible();
  await page.getByRole('link', { name: '分镜', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/episodes/${episodeId}/storyboard$`));
  await prepareReferences(page, projectId);
  await page.reload();
  await page.getByRole('button', { name: '收起 Agent' }).click();
  const confirmAll = page.getByRole('button', { name: '批量确认画面', exact: true });
  await expect(confirmAll).toHaveCount(0);
  await page.getByRole('button', { name: '本集批量生成分镜图', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '本集批量生成分镜图' });
  const batchResponse = page.waitForResponse(response => response.url().endsWith('/generation-batches') && response.request().method() === 'POST');
  await dialog.getByRole('button', { name: '提交 3 镜' }).click();
  await expect(dialog).toHaveCount(0);
  const batch = await (await batchResponse).json();
  await expect(page.getByRole('region', { name: '分镜图批次进度' }).getByText(`批次 #${batch.id}`, { exact: false })).toBeVisible();

  await page.reload();
  const continued = await send('继续做这集');
  expect(continued.messages![0].workflow!.batches!.map(item => item.id)).toEqual([batch.id]);
  expect((await (await page.request.get(`${path}/generation-batches?type=IMAGE`)).json()).map((item: { id: string }) => item.id)).toEqual([batch.id]);
  await expect.poll(async () => (await (await page.request.get(`${path}/workflow`)).json()).next.action).toBe('CONFIRM_SHOTS');
  await expect(page.getByRole('region', { name: '本集工作流' })).toHaveCount(0);
  await page.getByRole('link', { name: '视频', exact: true }).click();
  await expect(page.getByText('还没有确认的镜头。先到分镜页确认镜头。')).toBeVisible();
  await expect(page.getByRole('region', { name: '本集工作流' })).toHaveCount(0);
  await page.getByRole('link', { name: '分镜', exact: true }).click();
  await expect(page.getByRole('button', { name: '确认画面', exact: true })).toHaveCount(3);
  await confirmAll.click();
  const review = page.getByRole('dialog', { name: '批量确认分镜画面' });
  await expect(review.getByRole('img')).toHaveCount(3);
  await review.getByRole('button', { name: '取消', exact: true }).click();
  expect((await (await page.request.get(`${path}/workflow`)).json()).next.action).toBe('CONFIRM_SHOTS');
  await confirmAll.click();
  await review.getByRole('button', { name: '确认 3 张画面', exact: true }).click();
  await expect.poll(async () => (await (await page.request.get(`${path}/workflow`)).json()).next.action).toBe('GENERATE_VIDEOS');
  await expect(page.getByRole('region', { name: '本集工作流' })).toHaveCount(0);
  await page.getByRole('link', { name: '视频', exact: true }).click();
  await expect(page.getByRole('region', { name: '本集工作流' })).toHaveCount(0);

  await panel.getByRole('button', { name: '新建会话' }).click();
  const standalone = await send('继续');
  expect(standalone.messages![0].result!.message).toBe('本集还有镜头未生成视频');
  await expect(page.getByText('请检查已选首帧，然后提交视频生成', { exact: false })).toHaveCount(0);
  expect(await (await page.request.get(`${path}/generation-batches?type=VIDEO`)).json()).toEqual([]);
  const video = await send('给本集已选首帧且缺视频的镜头生成视频');
  expect(video.messages![1].result!.batchId).toBeTruthy();
  await expect.poll(async () => {
    const shots = await (await page.request.get(`${path}/shots`)).json();
    return shots.map((shot: { video: { ready: boolean; basedOn: string }; selectedImageId: string }) =>
      shot.video.ready && shot.video.basedOn === shot.selectedImageId);
  }).toEqual([true, true, true]);
  await expect(page.getByRole('button', { name: '已生成 3', exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: '本集工作流' })).toHaveCount(0);
});
