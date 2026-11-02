# P0 多平台导入合同与人工核算

导入文件采用下述两套约定格式，适配器将其映射到统一业务合同。两店属于同一业务 owner；两个来源 SKU 都映射到内部 SKU `MUG-01`。本样例无退货及采购附加成本。

## 字段映射

| 统一语义 | market-a | market-b |
| --- | --- | --- |
| 订单时间 / 付款时间 / 状态 / 币种 | `ordered_at` / `paid_at` / `status` / `currency` | `order_timestamp` / `payment_timestamp` / `order_state` / `order_currency` |
| 明细标识 / 来源订单 / SKU / 数量 | `external_line_id` / `external_order_id` / `external_sku` / `quantity` | `line_key` / `order_number` / `merchant_sku` / `units` |
| 售价 / 折扣 / 币种 / 采购批次 | `unit_price` / `discount_amount` / `currency` / `source_batch_id` | `unit_sale_price` / `line_discount` / `price_currency` / `purchase_lot` |
| 批次入库时间 / 数量 / 单位采购价 / 币种 | `received_at` / `quantity` / `unit_purchase_cost` / `currency` | `receipt_timestamp` / `received_units` / `cost_per_unit` / `cost_currency` |

状态映射：`settled` → 统一状态 `paid`。批次成本和销售收入分别按对应业务日期的汇率折算人民币，不能共用订单付款日汇率。

## 人工核算期望值

- market-a：销售净额 `(2 × 10 − 2) USD × 7.00 = 126.00 CNY`；批次成本 `2 × 4 USD × 6.90 = 55.20 CNY`；销售毛利 `70.80 CNY`。
- market-b：销售净额 `1 × 20 EUR × 8.00 = 160.00 CNY`；批次成本 `1 × 12.50 EUR × 7.80 = 97.50 CNY`；销售毛利 `62.50 CNY`。
- 两店合计：销售净额 `286.00 CNY`，采购成本 `152.70 CNY`，销售毛利 `133.30 CNY`。
- 所有销售行映射到同一个内部 SKU `MUG-01`；全局销量 `3` 件。

上述双店样例已通过 MySQL 集成接口核算，实际输出见 [actual-margin.json](actual-margin.json)：销售净额 286.00 元、采购成本 152.70 元、销售毛利 133.30 元。此处销售期间不包含 11 月 3 日的退款；另一个集成断言验证该日退款 4 USD、退货入库 1 件，分别按退款日 7.20 和采购日 6.90 折算，毛利影响为 -1.20 元。

## 当前核算接口

- `POST /api/v2/stores/{sid}/fx-rate-sets`：JSON 提交 `source`、`source_revision`、`rates`；每条 rate 包含 `base_currency`、`effective_date`、`rate`，报价币固定 CNY，方向为 1 单位原币兑换的人民币。保存后不覆盖旧版本；当前为手工导入接口，尚无 CSV 汇率上传或在线抓取。
- `POST /api/v2/margin-reports`：JSON 提交 `store_ids`、`fx_rate_set_id`、`start`、`end`。日期按 UTC 左闭右开，店铺须同 owner、全部可读；当前上限 20 店、366 天、10000 条销售/退款事件。
- 可选 `fixed_rate_date` 将销售、退款和采购全部按同一日期的指定版本汇率折算；省略时保持各业务日期口径。样例以 2026-11-02 固定 USD 7.10、EUR 8.00 后，毛利为 131.00 元。
- `GET /api/v2/margin-reports/{id}`：读取保存的输入清单和结果；`POST /api/v2/margin-reports/{id}/recompute` 用原输入复算，不覆盖旧报表。两个接口均重新检查所有目标店铺权限。
- `GET /api/v2/margin-reports?store_id={sid}`：列出包含该店铺且调用者仍有全部目标店铺权限的最近 50 份报表。工作台“人民币销售毛利”已接入汇率导入、生成、历史查看和复算。
- `POST /api/v2/margin-reports/{id}/revalue-costs`：仅所有者可执行。销售、退款、期间和汇率沿用该报表快照，采购成本采用同来源批次的最新修订，生成另一份报表并保存 `revalued_from`、原批次引用和成本版本边界；不改写销售行、批次分配或旧报表。SKU/币种变更拒绝自动处理。样例采购单价 4→5 USD 后，旧毛利仍为 133.30 元，重估为 119.50 元；重估可用现有比较接口追溯 -13.80 元成本影响。
- `POST /api/v2/margin-reports/compare`：提交 `baseline_id`、`target_id`。相同范围及口径下，先以基准汇率重算目标事实，分解业务数据和 FX 差额；固定口径须共享同一汇率集及固定日期。不能对缺汇率报表分解完整毛利。返回分量差额和变化事件前后来源；两份报表均重新授权。集成验证仅修改 USD 销售日汇率 7→8 时差额 +18.00 元全归 FX，取消 A 店订单时 -70.80 元全归业务数据。

当前按业务日期精确匹配汇率，不擅自向前取最近日期。缺失时 `complete=false`，`gross_margin_cny=null`；`known_margin_cny` 仅为完整定价事件的小计，未折算事件保留原币金额与日期。完整度分母只含本系统已发布的销售/退款事件，不代表外部店铺所有记录均已导入。
