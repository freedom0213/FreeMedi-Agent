import { existsSync } from 'node:fs'
import { chromium, defineConfig } from '@playwright/test'

const baseURL = 'http://127.0.0.1:4178'
const browserChannel = existsSync(chromium.executablePath()) ? 'chromium' : 'chrome'

export default defineConfig({
  testDir: './e2e',
  outputDir: '../target/frontend-e2e',
  fullyParallel: true,
  workers: 2,
  use: {
    baseURL,
    channel: browserChannel,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'pnpm build && node e2e/preview.mjs',
    url: baseURL,
    timeout: 120_000,
    reuseExistingServer: false,
  },
})
