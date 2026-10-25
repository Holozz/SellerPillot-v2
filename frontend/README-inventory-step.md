# 本步：商品与库存

将包里的 src 合并到 D:\Project\SellerPilot\frontend\src，只包含四个代码文件：

- ProductInventory.tsx：新增商品与库存组件。
- inventory.css：新增组件样式。
- api.ts：在已有代码中新增 getLowStockProducts，不改原有查询逻辑。
- App.tsx：导入并挂载新组件，位于经营数据下方。

无需新增依赖、数据库迁移或重启后端，前端继续使用 5188 端口。如果覆盖文件时 Vite 报找不到已经存在的 api 文件，在前端终端停止后重新运行 npm run dev，再刷新。

## 验收步骤

1. 选择已有商品的 aaaStore（ID 4），在页面下方找到“商品与库存”。进入店铺时自动查询商品列表，显示 SKU、名称、库存、单价和币种。
2. 将“查询范围”切换为“低库存”，阈值填 10，点击“查询库存”。结果应只包含 stock < 10 的商品。
3. 记下某个商品库存 N，将阈值设为 N，这个商品不应出现；再设为 N+1，这个商品应出现。数据超过 100 条时需要考虑显示上限。
4. 阈值填 0，应显示没有符合条件的商品；stock=0 不满足 stock<0。阈值填 1，库存为 0 的商品才会出现。
5. 切回“商品列表”并查询，原商品仍应存在。低库存结果为空，不等于店铺没有商品。
6. 换一个店铺，不能保留前一个店铺的商品或筛选结果；登录失效后需要重新登录。

本组件只读取数据。应用标题或从其他入口修改商品后，需要重新点击查询；订单导入不会自动扣减库存。

## 新增函数和数据流

App → ProductInventory → loadInventory → getProducts / getLowStockProducts → request → 后端。

### getLowStockProducts(storeId, threshold, signal)

新增于 api.ts。调用 GET /stores/{storeId}/products/low-stock，传递 threshold 和 limit=100。

URLSearchParams 负责将参数编码成查询字符串。signal 传入已有 request，用于取消请求；JWT、HTTP 错误也继续由 request 统一处理。

这里复用已存在的后端低库存函数。后端先检查店铺归属，再筛选 stock < threshold，最后按库存、商品 ID 排序并限制条数。不能先获取商品列表的前 100 条，再在浏览器中用 filter 查低库存，否则可能漏掉这 100 条之外的低库存商品。

### loadInventory(queryMode, threshold = 10)

这是本步的核心异步函数，查询类型决定调用哪个接口，阈值默认是 10，普通商品列表查询不使用它。

1. 判断 pending.current 是否已有请求，有则返回；否则创建 AbortController 并立即保存，阻止连续提交。
2. 设置 loading=true，使页面显示进度并禁用查询表单；清空上次错误。
3. 三元表达式按 queryMode 选择接口：products 调用已有 getProducts，low-stock 调用新增 getLowStockProducts。
4. await 等待请求；若 signal 已取消则停止，避免旧店铺响应更新页面。
5. 把商品数组和本次查询条件一起写入 result。商品列表的 threshold 记为 null，表示这个结果没有使用阈值。
6. catch 忽略主动取消；真实失败清空结果并提示；401 通知 App 清除登录状态。
7. finally 检查 pending.current === controller，只释放本次请求持有的锁并恢复空闲状态。组件卸载时已经清空 ref，不再给旧组件更新状态。

### handleQuery(event)

表单提交入口。preventDefault() 阻止整页刷新。

低库存模式下，将 thresholdText 转成数字，拒绝空输入、负数、小数和不能精确表示的整数。注意 Number('') 为 0，所以必须单独检查空字符串，不能把“没填”误认成“阈值为0”。通过校验后调用 loadInventory。

HTML 的 required/min/step/max 帮助用户在输入框中发现问题，函数内的检查保证调用逻辑也有明确条件。后端仍独立检查参数与店铺归属，前端校验不承担授权。

### useEffect 中的初始化与清理函数

进入店铺时调用 loadInventory('products') 自动读取商品。返回的清理函数在组件卸载时取消请求、清空 pending。

App 的 key 由账号 ID 和店铺 ID 组成。换店铺后旧组件卸载，新组件重新创建，筛选条件与结果不会沿用。

## 主要状态和语法

- QueryMode 是字符串联合类型，仅允许 products 或 low-stock。
- mode / thresholdText 是用户当前编辑的查询条件。输入框保存字符串，可表示正在输入时的空值。
- result 是已成功查询的数据及其条件快照。当前输入和查询结果分开保存，避免改了阈值就误以为旧表格已更新。
- criteriaChanged 比较输入与快照。不一致时显示“条件已修改”，旧表格上仍明确写着实际查询条件。
- loading 用于界面渲染，pending 是同步保存控制器的 ref。二者分别负责显示状态与阻止快速重复调用。
- products.map(...) 将每个商品变成一行表格；key={product.id} 帮助 React 区分各行。
- product.stock === 0 时才显示缺货标记。价格沿用后端字符串，不转成浮点数，也不跨币种相加。

## 接口限制

当前两个接口都最多返回 100 条，没有总数或下一页信息。本步不改后端，也不虚构分页。

页面显示“本次返回 N 条”。达到 100 条时提示可能还有未展示的商品，不将它当成店铺总数。普通商品列表按 ID 排列，低库存由后端按库存从少到多排列。

## 已完成验证

类型检查和生产构建通过。

浏览器使用模拟接口完成七组检查：初始商品加载与精确价格展示；低库存独立接口及边界；非法阈值与100条提示；桌面和390px手机布局；刷新、空结果和错误；连点与跨店铺取消；401退出登录与运行时错误检查。

测试未写真实业务数据或调用 GLM，临时测试浏览器与预览服务已关闭。覆盖到实际项目后的真实接口验收仍按上方步骤进行。
