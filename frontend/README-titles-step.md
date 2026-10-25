# 前端第三步：商品标题草稿与确认

2026-10-18：用户已确认上一阶段真实账号、GLM页面提问、追问与政策引用全部跑通。本轮在同一店铺工作台加入商品列表和标题修改闭环。

## 接入

交付包里的src目录合并到 `D:/Project/SellerPilot/frontend/src/`：替换api.ts、App.tsx、style.css，新增ProductTitles.tsx。App挂载商品标题组件，原AgentChat保持原有用途；本轮不增加依赖或后端迁移。

保持用户终端中的前后端服务运行，继续打开 http://127.0.0.1:5188 。新增区域位于聊天区域下方，标题为“商品标题”。源文件包只包含上述4个文件，保留实际vite.config.ts的5188配置。

## 页面流程

1. 登录、选择店铺，页面加载该店铺前100个商品，默认选中第一个。尚无商品时显示空状态，商品添加仍通过Swagger。
2. 选择商品、点击“生成标题草稿”。此时只创建pending草稿，不修改商品。
3. 对照原标题和建议标题，核对商品事实及措辞。勾选“我已核对建议标题”后，确认按钮才启用。
4. 点击“确认应用标题”，把明确的confirmed:true提交给后端；成功后显示应用时间并重新查询商品当前标题。
5. 遇到409旧标题冲突时阻止继续应用该草稿，要求重新生成与审核。确认响应中断时，保留已知草稿ID，可点击“查询草稿状态”检查实际结果。

## 接口与类型

所有新API沿用原request函数附带JWT、处理HTTP错误。

| 函数 | 输入和输出 | 接口 |
| --- | --- | --- |
| getProducts | 店铺ID、取消信号 -> Product数组 | GET /products?store_id={id}&limit=100 |
| generateTitleDraft | 店铺ID、商品数字ID、信号 -> TitleDraft | POST /stores/{id}/products/{product_id}/title-drafts |
| getTitleDraft | 店铺ID、草稿ID、信号 -> TitleDraft | GET /stores/{id}/title-drafts/{draft_id} |
| confirmTitleDraft | 店铺ID、草稿ID、信号 -> TitleDraft | POST /stores/{id}/title-drafts/{draft_id}/confirm，body为confirmed:true |

Product和TitleDraft是TypeScript类型说明。生成响应只含基本草稿字段，查询/确认响应增加创建人和应用信息，所以这些额外字段标为可选。前端类型提示不会对后端JSON或标题事实执行运行时校验。

## 新组件与函数

ProductTitles接收store及登录过期回调，像AgentChat一样按用户ID和店铺ID设置key。切店或退出会卸载组件、清空旧草稿和勾选状态，并取消浏览器等待。

| 函数 | 实现和职责 |
| --- | --- |
| runAction | 共用请求生命周期：接收操作类型和一个异步函数，创建控制器，设置忙状态，调用该函数，统一处理401、409和其他错误，最后释放锁。它本身不决定调用哪个业务接口。 |
| loadProducts | 读取当前店铺商品，更新列表；当前选中商品不在结果中时重新选择并清空旧草稿。初次显示、刷新和确认成功后共用。 |
| handleSelectProduct | 修改selectedId，清空上一个商品的草稿、勾选与冲突状态；正在请求时拒绝切商品。 |
| handleGenerate | 清理旧预览和确认勾选，创建一份新草稿并显示；不调用确认接口。 |
| handleConfirm | 先检查pending草稿、已勾选和无冲突，再请求确认；保存后端回执，然后重新读取实际商品列表。 |
| handleCheckDraft | 按当前草稿ID读取后端状态，更新预览；若已应用，再读取实际商品。查询本身不会重新生成或应用。 |

例如 `runAction('loading', loadProducts)` 把loadProducts这个函数交给runAction；runAction准备好signal后，才执行 `await operation(controller.signal)`。这是函数作为参数传递，与事件处理器的写法相通。

## 状态与显示

| 状态/引用 | 含义 |
| --- | --- |
| products、selectedId | 实际商品列表与选中商品 |
| draft | 当前生成或查询得到的草稿/历史回执，初始null |
| reviewed | 用户是否勾选核对，初始false；换商品或重新生成时清除 |
| conflict | 是否收到后端409，决定需要重新生成 |
| busy | null或loading/generating/checking/confirming，决定按钮和输入是否禁用及提示文字 |
| error | 接口错误提示 |
| pending.current | 当前AbortController；立即阻止重复点击发起第二个操作，取消时忽略旧结果 |

初次商品加载在useEffect中发起；清理时abort并清空ref，支持React开发StrictMode的建立/清理检查。finally仅清理属于自己的控制器，已取消的旧操作不改写新状态。

页面沿用已有字体和浅蓝灰/青色配色。商品用紧凑可滚动列表；草稿前后对照在桌面并排，手机单列，确认与查询按钮分行方便点击。仅通过用户动作显示真实状态，不展示虚构统计。

## 重要行为边界

- 核对勾选是页面交互门槛，权限、明确确认和原标题冲突仍由后端检查。页面不保证标题建议的事实性。
- 后端草稿回执是历史记录。查询已应用草稿时仍重新读取商品，避免把历史建议标题误当成商品最新标题。
- 确认成功而列表刷新失败时，已应用回执仍保留；可以“刷新商品”，不应把成功应用当作未提交。
- 确认网络中断时，页面保留的是上次已知草稿状态，实际可能已应用；优先查询草稿状态。对同一个draft_id重试确认仍沿用后端幂等规则。
- 生成请求不幂等；生成响应丢失可能已在后端留下草稿。切店或刷新页面不保证取消后端生成/应用，当前没有草稿历史列表或页面恢复功能。
- 商品仅显示前100项，不实现分页、商品编辑或创建；草稿建议在此页面只读，用户确认后应用。

## 验证记录

暂存代码已通过TypeScript检查、生产构建与11组浏览器流程检查。覆盖店铺商品过滤、生成不修改商品、明确勾选、确认请求体、历史回执与最新商品、409冲突、响应丢失后GET恢复、应用后列表刷新失败、切店旧回复隔离、空状态、401及手机布局。

浏览器使用本机无窗口Edge和模拟接口，未调用GLM、未修改实际业务数据；临时前端和浏览器结束后关闭。报告/截图在协作目录sellerpilot_frontend_titles_verification。复制到项目后，需要用户用真实商品完成本轮生成和确认验收。
