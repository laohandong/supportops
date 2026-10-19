import {defineConfig} from '@playwright/test';
import {resolve} from 'node:path';

export default defineConfig({
  testDir: '.', testMatch: '*.spec.mjs', workers: 1, fullyParallel: false, retries: 0,
  timeout: 180000, expect: {timeout: 15000},
  outputDir: resolve(process.env.RD_RESULTS || '.local/reliability-e2e-unconfigured/results'),
  reporter: [['list'], ['json', {outputFile: resolve(process.env.RD_RESULTS || '.local/reliability-e2e-unconfigured/results', 'playwright-private.json')}]],
  use: {baseURL: process.env.RD_BASE_URL, browserName: 'chromium', headless: true, viewport: {width: 1440, height: 960}, screenshot: 'only-on-failure', trace: 'off', video: 'off'},
});
