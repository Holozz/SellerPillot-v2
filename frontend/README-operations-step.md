# 本步：经营数据页面

把压缩包里的 src 合并到 D:\Project\SellerPilot\frontend\src。

仅包含四个代码文件：

- StoreOperations.tsx：新增页面组件，日期筛选、销售汇总、订单列表与分页。
- operations.css：新增组件样式，沿用已有字体、颜色和间距。
- api.ts：在已有版本末尾追加经营查询的类型与两个请求函数。
- App.tsx：导入组件，并在 CSV 导入区域下方挂载。保留了读取时已有的代码和注释。

不需要新增依赖或数据库迁移。继续使用原来的前后端启动方式和前端 5188 端口。

## 先跑通

1. 覆盖文件后打开或刷新前端，选择 aaaStore（ID 4）。
2. 找到页面下方“经营数据”，开始与结束日期都选择 2026-10-18，订单状态选“全部状态”，点击“查询经营数据”。
3. 确认可以看到之前导入的四个 SP-CSV-20261018-B71D 前缀订单。
4. 本批数据应贡献：有效订单 2 单、销量 5 件、销售总额 65.00 USD、退款 5.00 USD、净销售额 60.00 USD。如果当天原本有订单，汇总还会包括原有数据。
5. 把状态改为“已取消”。未点击查询时会提示条件已修改。点击查询后，列表只显示取消订单，销售汇总仍按已付款和已发货订单计算。
6. 修改日期并重新查询，应得到相应日期的数据或空结果。有超过 20 条订单时，使用上一页、下一页。
7. 再次导入 CSV 后，点击查询重新读取。页面不会自动刷新已展示的数据。

## 新增函数的责任和调用关系

调用链：App → StoreOperations → handleQuery / 分页按钮 → loadOperations → getSalesSummary + getOrderPage → 已有 request → 后端。

### todayInBeijing()

返回北京时间的今天日期，形式为 YYYY-MM-DD，用于两个日期输入框的初始值。

useState(todayInBeijing) 传入函数本身，让 React 在初始化状态时计算日期。后续重新渲染不需要重新生成初始值。

### buildRange(startDay, endDay)

输入用户选择的两个日期，先检查是否填写、顺序是否正确，再返回接口要求的 start_at / end_at。

页面中的结束日期包含当天。后端使用 [start_at, end_at)，上界不包含，所以把结束日期转换成下一天零点。例如选择 10 月 18 日至 18 日，会查询北京时间 18 日零点至 19 日零点。

这里明确使用 +08:00，随后用 toISOString() 转成 UTC。同一时刻的时区表示可以不同，不会改变查询范围。北京时间采用固定 UTC+8，因此加 24 小时就是下一天零点，不依赖浏览器所在时区。

### displayOrderTime(value)

将后端返回的 UTC 时间转换为北京时间展示。Intl.DateTimeFormat 指定 Asia/Shanghai，避免日期筛选按北京时间而列表按用户电脑时区显示。

### loadOperations(offset)

这是本步主要的异步函数，offset 表示跳过多少条订单。新查询传 0，下一页通常传 20、40。

1. 用 pending.current 保存 AbortController，立即占用请求锁。按钮的 loading 状态用于显示和禁用操作，ref 用于阻止 React 尚未重新渲染时的连续提交。
2. 调用 buildRange，把输入框的日期转换成后端时间区间。
3. 用 Promise.all 并发查询汇总和订单。两者可以独立请求，只有两者都成功才显示新结果。订单状态只传给订单列表接口。
4. 检查 signal 是否取消。切换店铺或退出登录后，不再使用旧响应。
5. 比较两份响应的 data_version。不同表示查询期间发生了导入，提示重查。这只是发现部分并发变化的检查，不会让两个独立请求变成一个数据库快照。
6. setResult 同时保存响应与本次使用的日期、状态。界面展示的是这份结果快照，后续编辑输入框不会偷偷改变旧结果的标签。
7. 捕获失败时取消另一个请求、清除旧结果并显示错误。401 通知 App 清除登录状态。
8. finally 在本次仍持有锁时释放锁并恢复按钮。组件卸载时，清理函数已经取消请求并清空 ref，不再更新旧组件。

### handleQuery(event)

表单提交入口。preventDefault() 阻止浏览器按传统表单方式刷新整页，再调用 loadOperations(0)。void 表示此处不使用异步函数返回的 Promise；请求内部已处理错误。

### getSalesSummary(storeId, range, signal)

请求 GET /stores/{storeId}/sales/summary，返回分币种的精确金额、有效订单数和销量。复用 request 自动附带 JWT 和统一错误处理。

### getOrderPage(storeId, range, status, offset, signal)

请求 GET /stores/{storeId}/orders。每页 20 条，通过 offset / has_more 翻页。status 为空时省略参数，让后端查询全部状态。

两个请求函数都使用 URLSearchParams 编码参数，避免手写 URL 时误处理时间与时区符号。

## 新增状态与类型

- startDay / endDay / status：当前输入框条件。
- result：上一次成功查询的完整结果及条件快照，初始为 null。
- criteriaChanged：比较当前输入与结果快照。改变条件后先提示重新查询，并暂时禁止翻旧结果的页。
- loading / error：请求进度与错误文案。
- pending：本次请求的同步锁与取消控制器。
- OrderStatus：四种允许的状态组成的 TypeScript 联合类型。
- SalesGroup：一个币种的销售汇总。金额仍是字符串，避免转为 JavaScript 浮点数后损失精度。
- QueryContext：两个接口共有的店铺、日期范围和数据版本字段。
- SalesSummary / OrderPage：两个后端响应的结构；extends 复用 QueryContext 字段。
- SalesRange：请求需要的两个带时区时间字符串。

App 使用账号 ID 与店铺 ID 组成 key。切换店铺时，React 会卸载旧组件并创建新组件，筛选、结果和请求状态一起重置。实际店铺归属仍由后端检查，key 不承担授权。

## 验证范围

已通过 TypeScript 检查和生产构建。

浏览器使用模拟接口通过七组检查：北京时间日期边界与显示、金额字符串精度及币种分开、状态筛选与分页、空数据与缺明细提示、版本变化与请求失败、连续提交与跨店铺取消、401 清登录。已检查桌面和 390px 手机宽度，表格可在自身区域横向滚动。

测试没有写入真实业务数据或调用 GLM，临时测试浏览器与预览进程已关闭。需要你覆盖文件后按上面的步骤，验证前端与真实后端联通。
