# P0 导入合同模拟样例

全部数据均为虚构模拟数据，仅演示两种不同导出列名如何映射到统一合同，不代表任何真实平台格式或接入能力。两店属于同一业务 owner；两个来源 SKU 都映射到内部 SKU `MUG-01`。本样例无退货及采购附加成本。

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

这只是 P0 手工核算夹具，尚未由导入器或报表实现验证。
