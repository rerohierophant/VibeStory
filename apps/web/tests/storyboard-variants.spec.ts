import { expect, test } from '@playwright/test';

test('compare image variants, regenerate one member, reject a stale preview, and adopt the current result', async ({ page, context, baseURL }) => {
  await context.route('**/*', route => {
    const url = route.request().url();
    return url.startsWith(baseURL + '/') || url.startsWith('data:') ? route.continue() : route.abort();
  });
  await page.goto('/login');
  await page.getByLabel('邮箱', { exact: true }).fill(process.env.WEB_TEST_EMAIL!);
  await page.getByLabel('密码', { exact: true }).fill(process.env.WEB_TEST_PASSWORD!);
  await page.getByRole('button', { name: '进入工作台' }).click();
  await expect(page.getByText('后端已连接', { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  const runtime = await (await page.request.get('/api/runtime')).json();
  expect([runtime.text.provider, runtime.image.provider, runtime.video.provider]).toEqual(['mock', 'mock', 'mock']);
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const headers = { [csrf.headerName]: csrf.token };
  const project = await page.request.post('/api/projects', { headers, data: { name: '出图方案验收', visualType: 'realistic' } });
  expect(project.status()).toBe(201);
  const projectId = (await project.json()).id;
  const episode = await page.request.post(`/api/projects/${projectId}/episodes`, { headers, data: {} });
  expect(episode.status()).toBe(201);
  const episodeId = (await episode.json()).id;
  const episodePath = `/api/episodes/${episodeId}`;
  const shotList = async () => (await page.request.get(`${episodePath}/shots`)).json();
  const variants = async () => (await (await page.request.get(`${episodePath}/storyboard-variants`)).json()).variants;
  expect((await page.request.put(`${episodePath}/story`, { headers, data: {
    story: '沈夜走进雨夜街道。\n便利店的门打开。\n积水反射出远处的路灯。',
  } })).ok()).toBe(true);
  expect((await page.request.post(`${episodePath}/breakdowns`, { headers, data: { replaceExisting: false } })).status()).toBe(202);
  await expect.poll(async () => (await shotList()).length).toBeGreaterThanOrEqual(2);

  await page.goto(`/projects/${projectId}/episodes/${episodeId}/storyboard`);
  await page.getByRole('button', { name: '全部确认', exact: true }).last().click();
  await expect.poll(async () => (await shotList())[0].status).toBe('confirmed');
  await page.getByRole('button', { name: '分镜出图方案', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '分镜出图方案' });
  async function submit(name: string, description: string, twoShots: boolean) {
    await dialog.getByRole('button', { name: '新建出图方案' }).click();
    await dialog.getByLabel('方案名称').fill(name);
    await dialog.getByLabel('选择第 1 镜', { exact: true }).check();
    if (twoShots) await dialog.getByLabel('选择第 2 镜', { exact: true }).check();
    await dialog.getByLabel('第 1 镜画面描述').fill(description);
    await dialog.getByRole('button', { name: `提交 ${twoShots ? 2 : 1} 个出图任务` }).click();
    await expect(dialog.getByRole('columnheader', { name: new RegExp(name) })).toBeVisible();
  }
  await submit('交代空间', '用远景交代空巷和路灯', true);
  await submit('突出脚步', '近景拍脚踩入积水', false);
  await expect(dialog.getByRole('img', { name: '交代空间 · 第 2 镜' })).toBeVisible();
  await expect(dialog.getByRole('img', { name: '突出脚步 · 第 1 镜' })).toBeVisible();
  expect((await shotList())[0].images).toEqual([]);

  const original = (await variants())[0];
  await dialog.getByRole('columnheader', { name: /交代空间/ }).getByRole('button', { name: '局部重出' }).click();
  await dialog.getByLabel('重出第 1 镜', { exact: true }).check();
  await dialog.getByLabel('第 1 镜画面描述').fill('俯拍追逐者走过路灯下');
  await dialog.getByRole('button', { name: '提交 1 个出图任务' }).click();
  await expect(dialog.getByRole('columnheader', { name: /交代空间 · 第 2 版/ })).toBeVisible();
  await expect(dialog.getByRole('img', { name: '交代空间 · 第 1 镜' })).toBeVisible();
  const revised = (await variants())[0];
  expect(revised.members[1].taskId).toBe(original.members[1].taskId);
  expect(revised.members[1].imageId).toBe(original.members[1].imageId);

  await page.reload();
  await page.getByRole('button', { name: '分镜出图方案', exact: true }).click();
  await dialog.getByLabel('采纳交代空间第 1 镜', { exact: true }).check();
  await dialog.getByRole('button', { name: '预览采纳 1 镜' }).click();
  await expect(dialog.getByRole('region', { name: '采纳预览' })).toBeVisible();
  const before = (await shotList())[0];
  expect((await page.request.patch(`/api/shots/${before.id}`, { headers, data: {
    description: '另一处已保存的新描述', expectedVersion: before.version,
  } })).ok()).toBe(true);
  await dialog.getByRole('button', { name: '确认采纳 1 镜' }).click();
  await expect(dialog.getByRole('alert')).toContainText('请重新比较');
  expect((await shotList())[0].selectedImageId ?? null).toBeNull();
  await dialog.getByRole('button', { name: '返回对比' }).click();
  await dialog.getByRole('button', { name: '预览采纳 1 镜' }).click();
  await expect(dialog.getByRole('region', { name: '采纳预览' })).toContainText('另一处已保存的新描述');
  await dialog.getByRole('button', { name: '确认采纳 1 镜' }).click();
  await expect(dialog.getByRole('status')).toContainText('已采纳 1 镜');
  const after = (await shotList())[0];
  expect(after.description).toBe('俯拍追逐者走过路灯下');
  expect(after.selectedImageId).toBe(revised.members[0].imageId);
  expect(after.images).toHaveLength(1);

  await dialog.getByRole('button', { name: '关闭', exact: true }).click();
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  const agent = page.getByRole('complementary', { name: 'Agent 对话' });
  await agent.getByRole('textbox', { name: 'Agent 指令' }).fill('预览采纳出图方案《突出脚步》的第 1 镜');
  await agent.getByRole('button', { name: '发送', exact: true }).click();
  await expect(agent.getByRole('img', { name: '待采纳分镜图' })).toBeVisible();
  await agent.getByRole('button', { name: '确认采纳这一镜' }).click();
  await expect(agent.getByText('已采纳分镜图和镜头参数')).toBeVisible();
  expect((await shotList())[0].description).toBe('近景拍脚踩入积水');
  await page.reload();
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  await expect(agent.getByText('已采纳分镜图和镜头参数')).toBeVisible();
});
