import { expect, type Page } from '@playwright/test';

export const referencePng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');

export async function prepareReferences(page: Page, projectId: string) {
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  for (const kind of ['characters', 'scenes']) {
    const response = await page.request.get(`/api/projects/${projectId}/${kind}`);
    expect(response.ok()).toBe(true);
    const assets: { id: string; selectedVersionId?: string }[] = await response.json();
    for (const asset of assets.filter(item => !item.selectedVersionId)) {
      const uploaded = await page.request.post(`/api/assets/${kind}/${asset.id}/versions`, {
        headers: { [csrf.headerName]: csrf.token },
        multipart: { file: { name: '参考图.png', mimeType: 'image/png', buffer: referencePng } },
      });
      expect(uploaded.ok(), await uploaded.text()).toBe(true);
    }
  }
}
