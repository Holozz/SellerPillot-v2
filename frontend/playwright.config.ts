import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir:'./e2e',workers:1,timeout:60000,
  use:{baseURL:'http://127.0.0.1:5190',headless:true,
    launchOptions:process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{},
    screenshot:'only-on-failure',trace:'retain-on-failure'},
  reporter:[['list'],['json',{outputFile:'../docs/verification/browser-results.json'}]],
})
