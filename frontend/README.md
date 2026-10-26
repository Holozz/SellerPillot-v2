# SellerPilot Java 前端

React、TypeScript、Vite 管理工作台。当前前端只连接 Java API。

## 本地启动

先启动 Java API（详见 [后端说明](../backend-java/README.md)），再执行：

```powershell
cd frontend
npm ci
npm run dev -- --port 5191
```

Vite 将 `/api` 与 `/api/v2` 请求代理到 `http://127.0.0.1:8081`。访问 http://127.0.0.1:5191。

## 功能

账号登录/注册、店铺选择、商品管理、CSV 订单与明细导入、经营统计、标题变更审批、团队权限。AI Agent/RAG 不属于当前 Java 版本。

## 验证

```powershell
npm run build
$env:PLAYWRIGHT_BASE_URL='http://127.0.0.1:5191'
$env:CHROME_PATH='C:\Program Files\Google\Chrome\Application\chrome.exe'
npx playwright test
```

Playwright 流程使用独立测试账号和 Java 后端；完整前置条件见后端 README。
