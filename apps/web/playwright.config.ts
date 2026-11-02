import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests', testMatch: '*.spec.ts', timeout: 90000, expect: { timeout: 15000 }, workers: 1, retries: 0,
  outputDir: process.env.WEB_TEST_ARTIFACTS || '../../data/verification/web-tests',
  reporter: [['list'], ['json', { outputFile: `${process.env.WEB_TEST_ARTIFACTS || '../../data/verification/web-tests'}/results.json` }]],
  use: { baseURL: process.env.WEB_TEST_URL, channel: 'chrome', headless: true,
    viewport: { width: 1440, height: 1000 }, screenshot: 'only-on-failure', trace: 'retain-on-failure' },
});
