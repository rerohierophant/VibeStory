import { test, expect } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { prepareReferences } from './reference-fixture';

test('regeneration keeps notes local until the Agent saves preferences and versioned recipes', async ({ page, context, baseURL }, testInfo) => {
  await context.route('**/*', route => route.request().url().startsWith(baseURL + '/') ? route.continue() : route.abort());
  await page.goto('/login');
  await page.getByLabel('邮箱', { exact: true }).fill(process.env.WEB_TEST_EMAIL!);
  await page.getByLabel('密码', { exact: true }).fill(process.env.WEB_TEST_PASSWORD!);
  await page.getByRole('button', { name: '进入工作台' }).click();
  await expect(page).toHaveURL(/\/$/);
  const assetLibrary = page.getByRole('navigation').getByRole('link', { name: /^资产库/ });
  const assetCategories = page.getByRole('group', { name: '资产类别', exact: true });
  const recipeTab = assetCategories.getByRole('button', { name: /^创作配方/ });
  const recipes = page.getByRole('region', { name: '创作配方', exact: true });
  async function checkAssetCounts(recipeCount: number) {
    let total = recipeCount;
    for (const [label, kind] of [['角色', 'characters'], ['场景', 'scenes']]) {
      const assets = await (await page.request.get(`/api/assets/${kind}`)).json();
      const category = assetCategories.getByRole('button', { name: new RegExp(`^${label}`) });
      await expect(category.getByText(String(assets.length), { exact: true })).toBeVisible();
      total += assets.length;
    }
    await expect(recipeTab.getByText(String(recipeCount), { exact: true })).toBeVisible();
    await expect(assetLibrary.getByText(String(total), { exact: true })).toBeVisible();
  }
  await assetLibrary.click();
  await checkAssetCounts(0);
  await expect(assetCategories.getByRole('button', { name: /^角色/ })).toHaveAttribute('aria-pressed', 'true');
  const sceneTab = assetCategories.getByRole('button', { name: /^场景/ });
  await sceneTab.click();
  await expect(sceneTab).toHaveAttribute('aria-pressed', 'true');
  await recipeTab.click();
  await expect(recipes.getByText('还没有创作配方', { exact: true })).toBeVisible();
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const headers = { [csrf.headerName]: csrf.token };
  async function post(path: string, data?: object) {
    const response = await page.request.post(path, { headers, data });
    expect(response.ok(), await response.text()).toBe(true);
    return response.json();
  }
  async function setup(name: string, story: string) {
    const project = await post('/api/projects', { name, visualType: 'realistic' });
    const episode = await post(`/api/projects/${project.id}/episodes`, { title: name });
    const path = `/api/episodes/${episode.id}`;
    expect((await page.request.put(`${path}/story`, { headers, data: { story } })).ok()).toBe(true);
    await post(`${path}/breakdowns`, { replaceExisting: false });
    await expect.poll(async () => (await (await page.request.get(`${path}/shots`)).json()).length).toBeGreaterThan(0);
    const shots = await (await page.request.get(`${path}/shots`)).json();
    return { project, episode, shot: shots[0], url: `/projects/${project.id}/episodes/${episode.id}/storyboard` };
  }
  const first = await setup('图片经验来源', '沈夜走进雨巷，在灯下捡起一封信。\n他打开信封，看到熟悉的字迹。');
  await prepareReferences(page, first.project.id);
  const initial = await post(`/api/shots/${first.shot.id}/image-tasks`, {});
  await expect.poll(async () => (await (await page.request.get(`/api/tasks/${initial.taskId}`)).json()).ready).toBe(true);
  await page.goto(first.url);
  const agent = page.getByRole('complementary', { name: 'Agent 对话' });
  async function send(instruction: string, reply: string) {
    await agent.getByLabel('Agent 指令').fill(instruction);
    await agent.getByRole('button', { name: '发送', exact: true }).click();
    await expect(agent.getByText(reply, { exact: false }).first()).toBeVisible();
  }
  const experiencePath = (id: string) => `/api/image-experience/targets/SHOT/${id}`;
  const read = async (id: string) => (await page.request.get(experiencePath(id))).json();
  const regen = page.getByRole('dialog', { name: '再生成一版' });
  const recipeLink = page.getByRole('navigation').getByRole('link', { name: /^创作配方/ });
  const regenerate = page.getByRole('button', { name: '再生成一版', exact: true }).first();
  await expect(recipeLink).toHaveText(/创作配方\s*0/);
  await expect(page.getByRole('button', { name: '纠正与偏好', exact: true })).toHaveCount(0);
  await regenerate.click();
  await expect(regen.getByLabel('画面描述', { exact: true })).toBeVisible();
  await regen.getByText('更多出图设置', { exact: true }).click();
  await expect(regen.getByLabel('生成依据', { exact: true })).toHaveValue('');
  await regen.getByLabel('生成依据', { exact: true }).selectOption(initial.taskId);
  await expect(regen.getByRole('img', { name: '生成依据图片' })).toBeVisible();
  await regen.getByLabel('补充说明（选填）').fill('面容自然一点，颜色柔和一点');
  await regen.getByRole('button', { name: '生成新候选', exact: true }).click();
  await expect(regen).toHaveCount(0);
  await expect.poll(async () => (await read(first.shot.id)).creations[0]?.resultUrl).toBeTruthy();
  const before = await read(first.shot.id);
  expect(before.creations[0].sourceTaskId).toBe(initial.taskId);
  expect(before.creations[0].prompt).toContain('面容自然一点，颜色柔和一点');
  expect(before.creations[0].decision).toBe('PENDING');
  expect(before.preferences).toHaveLength(0);
  expect(before.recipes).toHaveLength(0);
  await send('满意第1镜，记住为个人偏好', '已记录满意结果并保存');
  await send('保存第1镜为配方《人物柔光配方》：人物形象自然，光线 {{lighting}}', '已保存配方「人物柔光配方」');
  await page.getByRole('button', { name: '收起 Agent', exact: true }).click();
  await recipeLink.click();
  await expect(recipeLink).toHaveAttribute('aria-current', 'page');
  await expect(recipeLink).toHaveText(/创作配方\s*1/);
  await recipes.getByText('人物柔光配方', { exact: false }).click();
  await expect(recipes.getByText('人物形象自然，光线 {{lighting}}', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '折叠侧边栏', exact: true }).click();
  await expect(recipeLink).toHaveAttribute('aria-current', 'page');
  await page.getByRole('button', { name: '展开侧边栏', exact: true }).click();
  const sourceEvidence = await read(first.shot.id);
  expect(sourceEvidence.creations[0].decision).toBe('ACCEPTED');

  // The Mock splitter emits no character names. Seed a character in this isolated verification project.
  const database = new URL(process.env.DATABASE_URL!.replace(/^jdbc:/, ''));
  expect(String(first.project.id)).toMatch(/^\d+$/);
  execFileSync(process.env.PSQL || '/opt/homebrew/opt/postgresql@16/bin/psql', ['-Xq', '-v', 'ON_ERROR_STOP=1', '-c',
    `insert into character_asset(user_id,project_id,name) select user_id,id,'沈夜' from project where id=${first.project.id}`], {
    env: { ...process.env, PGHOST: database.hostname, PGPORT: database.port || '5432',
      PGDATABASE: database.pathname.slice(1), PGUSER: process.env.DATABASE_USER || 'vibestory',
      PGPASSWORD: process.env.DATABASE_PASSWORD || '' },
  });
  for (const [kind, type] of [['characters', 'CHARACTER'], ['scenes', 'SCENE']] as const) {
    const [asset] = await (await page.request.get(`/api/projects/${first.project.id}/${kind}`)).json();
    await page.goto(`/projects/${first.project.id}/${kind}`);
    const card = page.locator('article').filter({ has: page.getByRole('heading', { name: asset.name, exact: true }) });
    await expect(card.getByRole('button', { name: '纠正与偏好', exact: true })).toHaveCount(0);
    await expect(card.getByRole('button', { name: type === 'CHARACTER' ? '上传角色图' : '上传场景图' })).toBeVisible();
    if (!asset.selectedVersionId) await card.getByRole('button', { name: '生成一版', exact: true }).click();
    await card.getByRole('button', { name: '再生成一版', exact: true }).click();
    const getAsset = async () => (await (await page.request.get(`/api/projects/${first.project.id}/${kind}`)).json())
      .find((item: { id: string }) => item.id === asset.id);
    const selectedId = (await getAsset()).selectedVersionId;
    expect(selectedId).toBeTruthy();
    const selectedThumbnail = card.getByRole('button', { name: /^设为基准图：/ }).first();
    await expect(selectedThumbnail).toBeVisible();
    await expect(selectedThumbnail).toHaveAttribute('aria-pressed', 'true');
    const assetNote = type === 'CHARACTER' ? '衣服颜色淡一点' : '窗外增加薄雾';
    await regen.getByLabel('补充说明（选填）').fill(assetNote);
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');
    await expect(regen.getByRole('button', { name: '上传参考图' })).toBeEnabled();
    await regen.getByLabel('本次生成参考图', { exact: true }).setInputFiles({ name: 'reference.png', mimeType: 'image/png', buffer: png });
    await expect(regen.getByRole('img', { name: '本次生成参考图预览' })).toBeVisible();
    await regen.getByRole('button', { name: '生成新候选', exact: true }).click();
    await expect(regen).toHaveCount(0);
    await expect.poll(async () => {
      const result = await (await page.request.get(`/api/image-experience/targets/${type}/${asset.id}`)).json();
      return result.creations[0]?.resultUrl ? result.creations[0].instruction : null;
    }).toBe(assetNote);
    const updated = await getAsset();
    expect(updated.selectedVersionId).toBe(selectedId);
    expect(updated.versions).toHaveLength(2);
  }

  const destination = await setup('图片经验复用', '许星遥在花园里摘下一朵白花。\n她转身走向清晨的湖边。');
  await prepareReferences(page, destination.project.id);
  await page.goto(destination.url);
  await page.getByRole('button', { name: '生成分镜图', exact: true }).first().click();
  await expect.poll(async () => (await read(destination.shot.id)).examples.length).toBeGreaterThan(0);
  const ordinary = (await read(destination.shot.id)).examples[0];
  expect(ordinary.prompt).toContain('面容自然一点，颜色柔和一点');
  const recipe = (await read(destination.shot.id)).recipes.find((item: { name: string }) => item.name === '人物柔光配方');
  await regenerate.click();
  await regen.getByText('更多出图设置', { exact: true }).click();
  await expect(regen.getByLabel('生成依据', { exact: true })).toHaveValue('');
  await regen.getByText('使用创作配方（选填）', { exact: true }).click();
  await regen.getByLabel('创作配方', { exact: true }).selectOption(recipe.versions[0].id);
  await regen.getByLabel('配方参数 lighting').fill('清晨侧逆光');
  await regen.getByRole('button', { name: '生成新候选', exact: true }).click();
  await expect.poll(async () => (await read(destination.shot.id)).creations[0]?.resultUrl).toBeTruthy();
  const applied = (await read(destination.shot.id)).creations[0];
  expect(applied.learning.recipe.version).toBe(1);
  expect(applied.learning.recipe.parameters.lighting).toBe('清晨侧逆光');
  expect(applied.prompt).toContain('清晨侧逆光');
  expect(applied.prompt).not.toContain('沈夜');
  await page.goto('/');
  await assetLibrary.click();
  await checkAssetCounts(1);
  await recipeTab.click();
  await recipes.getByText('人物柔光配方', { exact: false }).click();
  await recipes.getByRole('button', { name: '编辑为新版本', exact: true }).click();
  await recipes.getByLabel('配方要求').fill('人物形象自然，避免浓妆，光线 {{lighting}}');
  await recipes.getByRole('button', { name: '保存配方版本', exact: true }).click();
  await expect(recipes.getByRole('status')).toContainText('v2');
  await page.reload();
  await expect(recipes.getByText('分镜 · v2', { exact: true })).toBeVisible();
  await expect(recipeTab).toHaveAttribute('aria-pressed', 'true');
  await checkAssetCounts(1);
  const final = await read(destination.shot.id);
  expect(final.recipes.find((item: { id: string }) => item.id === recipe.id).versions).toHaveLength(2);
  expect(final.creations[0].learning.recipe.version).toBe(1);
  expect(final.creations[0].learning.preferences[0].rule).toBe('面容自然一点，颜色柔和一点');
  await page.goto(destination.url);

  const readShot = async () => (await page.request.get(`/api/shots/${destination.shot.id}`)).json();
  const beforeTrial = await readShot();
  for (const [index, direction] of ['远景交代花园和湖面', '特写手指捏住白花'].entries()) {
    await send(`生成第1镜分镜图：${direction}`, '已按要求提交图片候选');
    await expect.poll(async () => (await readShot()).images.length).toBe(beforeTrial.images.length + index + 1);
    expect((await read(destination.shot.id)).creations[0].prompt).toContain(direction);
  }
  expect(await readShot()).toMatchObject({
    description: beforeTrial.description, selectedImageId: beforeTrial.selectedImageId,
  });
  await writeFile(testInfo.outputPath('image-learning-evidence.json'), JSON.stringify({ sourceEvidence, ordinary, applied, final }, null, 2) + '\n');
});
