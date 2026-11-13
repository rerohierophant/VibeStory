import { randomUUID } from 'node:crypto';
import { test, expect } from '@playwright/test';

test('a new creator registers, stays signed in on reload, and can sign back in', async ({ page }) => {
  const email = `creator-${randomUUID()}@example.test`;
  const password = 'test-password';
  await page.goto('/login');
  await page.getByRole('button', { name: '注册账号', exact: true }).click();
  await expect(page.getByRole('heading', { name: '创建账号' })).toBeVisible();
  await page.getByLabel('邮箱', { exact: true }).fill(email);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByLabel('确认密码', { exact: true }).fill('different-password');
  const submit = page.getByRole('button', { name: '注册并进入工作台', exact: true });
  await submit.click();
  await expect(page.getByRole('status')).toHaveText('两次输入的密码不一致');

  await page.getByLabel('确认密码', { exact: true }).fill(password);
  await submit.click();
  await expect(page).toHaveURL(/\/$/);
  await page.getByRole('button', { name: '新建项目', exact: true }).first().click();
  await page.getByPlaceholder('例如：长夜将明').fill('注册后的首个项目');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await expect(page).toHaveURL(/\/projects\/\d+$/);
  await page.goto('/settings');
  await page.reload();
  await expect(page.getByText(`当前登录：${email}`, { exact: false })).toBeVisible();
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);

  await page.getByRole('button', { name: '注册账号', exact: true }).click();
  await page.getByLabel('邮箱', { exact: true }).fill(email.toUpperCase());
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByLabel('确认密码', { exact: true }).fill(password);
  await submit.click();
  await expect(page.getByRole('status')).toHaveText('该邮箱已注册，请直接登录');
  await page.getByRole('button', { name: '返回登录', exact: true }).click();
  await expect(page.getByLabel('邮箱', { exact: true })).toHaveValue(email.toUpperCase());
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '进入工作台', exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByText('注册后的首个项目', { exact: true })).toBeVisible();
});
