# SellerPilot 前端控制台

开发端口5188，React + TypeScript + Vite。当前覆盖已有21个业务接口。注册、建店、商品CRUD、库存、订单/商品销量、CSV、聊天和标题确认都有页面入口。

## 启动

后端继续在backend运行python -m uvicorn main:app --reload。前端另一个终端：

```powershell
cd D:\Project\SellerPilot\frontend
npm ci
npm run dev
```

访问http://127.0.0.1:5188。已有依赖且锁文件未变时只需npm run dev。重组后建议在前端终端Ctrl+C后重新启动，清理旧模块图；不需要安装新的依赖。生产构建为npm run build，生产API代理需单独部署。

## 代码位置

| 目录 | 作用 |
| --- | --- |
| src/app | App管理账号/当前店铺，StoreWorkspace管理模块状态和布局 |
| src/features/auth | 注册、登录与表单请求状态 |
| src/features/stores | 创建、选择、读取店铺详情 |
| src/features/products | 商品新增、详情、编辑、库存、低库存及明确确认删除 |
| src/features/analytics | 订单、分币种汇总、商品销量和各自分页 |
| src/features/chat | Agent问答、追问和政策引用 |
| src/features/imports | CSV模板、文件上传、回执和行级错误 |
| src/features/titles | 草稿生成、核对、应用和状态查询 |
| src/shared/api | client统一JWT/错误/204，其余文件按业务封装接口 |
| src/shared/types.ts | 共用类型；类型只提供开发期约束，不校验真实JSON |
| src/styles | tokens设计变量、base基础布局、模块样式 |

完整[接口覆盖与函数说明](../docs/接口覆盖与结构.md)及[演示验收](../docs/演示验收.md)。

## 状态与操作边界

- 注册成功切回登录，不保存密码。JWT仅存当前标签页sessionStorage，GLM密钥仅留后端.env。
- App选中服务器返回的店铺；创建商品的store_id来自当前店铺，不开放任意填写。
- 已访问模块用hidden隐藏，保留聊天、草稿和文件；key按账号/店铺重建工作区，切店清空状态。
- useState控制页面渲染，useRef保存同步请求锁，AbortController取消等待；取消等待不保证撤销服务器已经收到的写入。
- 商品详情/编辑会先GET读取当前值；PUT更新名称、价格、币种与库存，SKU不允许更换。
- 删除需要二次明确确认。无关联商品返回204，client不再尝试解析JSON；有关联订单或草稿返回409，页面保留商品和原因。
- 金额以精确字符串发送和展示，不用JavaScript浮点数汇总金额。库存要求非负整数。
- 写入成功而刷新失败时会明确提示，避免用户重复创建。若写入响应本身丢失，当前商品创建没有请求级幂等保证。
- 销售汇总和商品销量只统计paid/shipped，订单状态只筛选列表。日期按北京时间，结束日包含全天。
- 商品与店铺最多读取100条，页面披露限制；未新增虚假的总数或分页。订单和商品销量使用真实offset/has_more分别分页。
- 修改数据后，其他已打开模块需要主动刷新。页面刷新不恢复历史聊天或草稿界面。

## 验证

控制台改造通过类型检查、生产构建和8组浏览器模拟流程；桌面1440px和手机390px截图已检查。使用独立MySQL的9项回归通过，后端拆分前后OpenAPI路径/模型/声明状态码一致。新界面实际GLM联动待用户验证，历史版本真实问答/追问/引用已由用户确认。
