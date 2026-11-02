import { test, expect, type Page } from '@playwright/test';

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
  await page.locator('article').first().getByRole('button').first().click();
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
