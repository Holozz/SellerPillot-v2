import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir:'./e2e',workers:1,timeout:60000,
  testMatch:'**/java.spec.ts',
  use:{baseURL:process.env.PLAYWRIGHT_BASE_URL||'http://127.0.0.1:5191',headless:true,
    launchOptions:process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{}},
  reporter:[['list'],['json',{outputFile:'../docs/verification/java/browser-results.json'}]],
})
