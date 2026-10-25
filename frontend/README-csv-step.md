# 前端第四步：CSV导入

本轮接入已有订单和订单明细上传API。新增的重要异步流程、文件传输和错误处理语句附有语义注释，解释其作用及为何需要。

## 接入与流程

将包内src目录合并到 `D:/Project/SellerPilot/frontend/src/`，替换api.ts、App.tsx、style.css，新增CsvImports.tsx。保留实际5188端口配置，不增加依赖或数据库迁移。

前后端由用户在自己的可见终端运行。打开 http://127.0.0.1:5188 ，登录并选择店铺，在商品标题下方找到“经营数据导入”。先选“订单”，上传固定格式文件，再选“订单明细”上传对应文件。已成功导入的每个文件有独立回执。

## 模板

订单示例：

```csv
external_order_id,ordered_at,status,currency
DEMO-CSV-001,2026-10-18T10:00:00+08:00,paid,USD
```

明细示例：

```csv
external_order_id,line_no,sku,quantity,unit_price,refund_amount
DEMO-CSV-001,1,REPLACE-SKU,2,20.00,0.00
```

REPLACE-SKU需替换为目标店铺实际已有商品的SKU，并保证商品币种为USD。先导入对应订单。文件采用UTF-8编码，支持BOM；表头及顺序固定。订单时间需带时区偏移，金额、数量与关联规则由后端验证。导入历史记录不扣减当前商品库存。

## 函数与语句

| 函数/类型 | 作用、输入与输出 |
| --- | --- |
| CsvImports | 接收当前店铺及登录过期回调，管理导入类型、文件、等待状态、结果与错误，返回上传表单 |
| validateCsvFile | 输入File对象，按后端同样的5 MiB上限和.csv扩展名快速检查；返回错误字符串或空字符串，不读取CSV内容 |
| handleKindChange | 输入订单/明细类型，切换后清空旧文件、回执和错误；input的key变化也重建原生文件选择框 |
| handleFileChange | 输入选中的File或null，保存新文件并清理不再匹配的旧回执/错误 |
| handleUpload | 接收表单事件，校验文件、设置请求引用和等待状态、调用importCsv；成功显示回执，错误显示说明与行级明细，401通知父组件退出，最后释放引用和等待状态 |
| importCsv | 接收店铺ID、导入类型、File和信号，用FormData附加file字段并调用原request，返回ImportResult |
| ApiError | 在原status/message基础上增加可选行级issues列表；普通错误默认为空，既有调用方式兼容 |

公共request现在区分JSON和FormData。原JSON请求继续设置application/json；FormData请求由浏览器生成multipart/form-data及boundary。boundary分隔文件各部分，不能直接沿用JSON请求头。

后端CSV校验失败返回detail对象：message为总提示，errors包含row/field/reason。request保留这份明细到ApiError.issues，handleUpload取出后显示表格。普通登录错误的detail字符串仍按原规则显示；无法解析为JSON的代理错误则回退到HTTP状态提示。

```text
点击上传 -> handleUpload -> importCsv -> request
  -> 浏览器生成multipart请求，附JWT
  -> /stores/{store_id}/imports/orders 或 /order-items
  -> 后端校验、权限、事务及内容去重
  -> 回执或行级错误 -> 更新页面状态
```

pending.current是立即生效的请求锁；uploading是驱动页面禁用与按钮文字的状态。切店或退出卸载组件时abort浏览器等待，并忽略迟到的旧店铺结果。取消浏览器等待不保证取消后端导入。

HEADERS使用Record<ImportKind, string>描述两种类型与表头的对应关系。File、FormData都是浏览器提供的对象。event.target.files?.[0]取出第一个文件，未选文件时用null。文件输入框不使用value控制，换类型时用key重建它，使浏览器显示与React状态一致。

## 回执和边界

- 成功显示import_job_id、文件数据行数、该文件首次导入时的data_version。
- duplicate为true表示同店铺、同导入类型、相同文件字节内容已导入，本次未再次写入；改文件名不影响去重。返回原版本，不代表店铺当前最新版本，也不会恢复旧文件里的旧值。
- 两个文件各自成事务。一个文件失败整批回滚，不撤销另一份已成功文件。跨店铺权限、实际CSV内容和业务关联仍由后端检查。
- 网络中断可能发生在后端提交之后，页面不声称必定回滚。保留文件选择，用户可重传相同原文件，由后端返回实际导入或重复记录。
- 当前只选择单个文件并手动上传，最多5 MiB/10000条。accept仅是选择器提示，前端快速检查不替代后端校验。
- 行级错误先展示50项并提示总数，避免大文件导致页面渲染过多节点。导入后重新提问可以获取更新后的经营数据；已有历史答案不会自动重算。

## 验收与验证

复制后用自己的演示文件依次验证：订单成功、原文件重复、明细成功、非法行错误、店铺切换与清理。注意这些导入会真实写入当前店铺，应使用准备好的演示数据。

暂存代码通过类型检查、生产构建和十组浏览器流程检查，报告与截图见协作目录sellerpilot_frontend_csv_verification。浏览器测试使用模拟API，不写真实业务数据、不调用模型。现有后端事务/权限验证记录仍见项目根README；本轮复制后需完成真实接口验收。

下一步接按日期查询的销售汇总页面。
