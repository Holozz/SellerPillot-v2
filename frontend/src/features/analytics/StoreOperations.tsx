import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, getOrderPage, getSalesSummary, getProductSales } from '../../shared/api'
import type { OrderPage, OrderStatus, SalesRange, SalesSummary, Store, ProductSalesPage } from '../../shared/api'

interface Props {
  store: Store
  onSessionExpired: () => void
}

interface QueryResult {
  // 保存结果对应的筛选条件，避免改了输入框后把旧结果误认成新结果。
  startDay: string
  endDay: string
  status: OrderStatus | ''
  summary: SalesSummary
  orders: OrderPage
  productSales: ProductSalesPage
}

const statusLabels: Record<OrderStatus, string> = {
  pending: '待付款', paid: '已付款', shipped: '已发货', cancelled: '已取消',
}

function todayInBeijing() {
  // 先将当前时间平移到UTC+8，再截取UTC日期；不依赖用户电脑所在时区。
  return new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

function buildRange(startDay: string, endDay: string): SalesRange {
  if (!startDay || !endDay || startDay > endDay) {
    throw new Error('请选择完整日期，开始日期不能晚于结束日期。')
  }
  // 日期输入框只提供日期。这里明确将它解释为北京时间的当天零点。
  const start = new Date(`${startDay}T00:00:00+08:00`)
  const end = new Date(`${endDay}T00:00:00+08:00`)
  // 用户选“到18日”应包含18日全天；后端使用不包含上界的时间区间，
  // 因此传19日零点，不能传18日23:59:59而遗漏最后一秒内的数据。
  end.setTime(end.getTime() + 24 * 60 * 60 * 1000)
  return { start_at: start.toISOString(), end_at: end.toISOString() }
}

function displayOrderTime(value: string) {
  // 后端返回UTC时间，展示时统一转为北京时间，与筛选日期保持一致。
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).format(new Date(value))
}

export default function StoreOperations({ store, onSessionExpired }: Props) {
  const [startDay, setStartDay] = useState(todayInBeijing)
  const [endDay, setEndDay] = useState(todayInBeijing)
  const [status, setStatus] = useState<OrderStatus | ''>('')
  const [result, setResult] = useState<QueryResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  const criteriaChanged = result !== null && (
    startDay !== result.startDay || endDay !== result.endDay || status !== result.status
  )

  useEffect(() => {
    return () => {
      // 父组件切换店铺或退出登录时卸载本组件，取消旧店铺的在途请求。
      pending.current?.abort()
      pending.current = null
    }
  }, [store.id])

  async function loadOperations(offset: number, productOffset = 0) {
    // ref立即加锁；setLoading要等React重新渲染，单靠按钮禁用挡不住快速连点。
    if (pending.current) return
    const controller = new AbortController()
    pending.current = controller
    setLoading(true)
    setError('')
    try {
      const range = buildRange(startDay, endDay)
      // 三个GET并发查询；订单和商品销量分别分页，状态筛选只影响订单。
      const [summary, orders, productSales] = await Promise.all([
        getSalesSummary(store.id, range, controller.signal),
        getOrderPage(store.id, range, status, offset, controller.signal),
        getProductSales(store.id, range, productOffset, controller.signal),
      ])
      if (controller.signal.aborted) return
      // 三个请求不是同一数据库快照。发现版本不同就拒绝拼成一份结果，提示重查。
      if (summary.data_version !== orders.data_version || summary.data_version !== productSales.data_version) {
        throw new Error('查询期间店铺数据发生了变化，请重新查询。')
      }
      // 一次保存完整结果及其筛选快照，避免一半新数据、一半旧数据。
      setResult({ startDay, endDay, status, summary, orders, productSales })
    } catch (cause) {
      if (controller.signal.aborted) return
      // 其中一个请求失败后取消其他请求，避免无用的请求继续等待。
      controller.abort()
      setResult(null)
      if (cause instanceof ApiError && cause.status === 401) onSessionExpired()
      else setError(cause instanceof Error ? cause.message : '查询失败，请稍后重试。')
    } finally {
      // 只有本次请求仍持有锁时才收尾，避免清除另一次请求的状态。
      if (pending.current === controller) {
        pending.current = null
        setLoading(false)
      }
    }
  }

  function handleQuery(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    // 新查询总是从第一页开始，不能沿用上一次筛选的分页位置。
    void loadOperations(0)
  }

  return (
    <section className="operations-panel" aria-labelledby="operations-heading" aria-busy={loading}>
      <div className="chat-heading">
        <div><h2 id="operations-heading">经营数据</h2><p>{store.name} 的订单与销售汇总</p></div>
      </div>
      <form className="operations-form" onSubmit={handleQuery}>
        <label>开始日期<input type="date" required value={startDay} disabled={loading}
          onChange={(event) => setStartDay(event.target.value)} /></label>
        <label>结束日期<input type="date" required value={endDay} disabled={loading}
          onChange={(event) => setEndDay(event.target.value)} /></label>
        <label>订单状态<select value={status} disabled={loading}
          onChange={(event) => setStatus(event.target.value as OrderStatus | '')}>
          <option value="">全部状态</option>
          {Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        <button className="primary-button" type="submit" disabled={loading}>
          {loading ? '正在查询…' : '查询经营数据'}
        </button>
      </form>
      <p className="hint">日期按北京时间，包含开始和结束当天。订单状态只筛选下方订单列表。</p>
      <p className="hint">销售汇总仅统计已付款、已发货订单；金额按币种分别展示，净销售额为销售总额减退款。</p>
      {error && <p className="error-message" role="alert">{error}</p>}
      {criteriaChanged && <p className="operations-warning" role="status">筛选条件已修改，请点击查询更新结果。</p>}
      {!result && !error && !loading && <p className="hint">选择日期后，点击查询查看数据。导入 CSV 后也需要重新查询。</p>}
      {result && <>
        <p className="operations-context">已查询：{result.startDay} 至 {result.endDay}（北京时间） · 数据版本 {result.summary.data_version}</p>
        <h3>销售汇总</h3>
        {result.summary.has_orders_without_items && <p className="operations-warning" role="status">
          存在尚未导入明细的有效订单，订单数已计入，销量与金额可能不完整。请补充明细后重新查询。
        </p>}
        {result.summary.by_currency.length === 0 ? <p className="hint">这段日期没有已付款或已发货订单。</p> :
          <div className="operations-table" role="region" aria-label="销售汇总表" tabIndex={0}>
            <table><thead><tr><th>币种</th><th>有效订单</th><th>销量</th><th>销售总额</th><th>退款</th><th>净销售额</th></tr></thead>
              <tbody>{result.summary.by_currency.map((group) => <tr key={group.currency}>
                <td>{group.currency}</td><td>{group.order_count}</td><td>{group.units_sold}</td>
                <td>{group.gross_sales}</td><td>{group.refund_amount}</td><td>{group.net_sales}</td>
              </tr>)}</tbody>
            </table>
          </div>}
        <h3>订单列表 <small>筛选：{result.status ? statusLabels[result.status] : '全部状态'}</small></h3>
        {result.orders.items.length === 0 ? <p className="hint">没有符合条件的订单。</p> :
          <div className="operations-table" role="region" aria-label="订单列表表格" tabIndex={0}>
            <table><thead><tr><th>订单号</th><th>下单时间（北京时间）</th><th>状态</th><th>币种</th></tr></thead>
              <tbody>{result.orders.items.map((order) => <tr key={order.id}>
                <td className="operations-order-id">{order.external_order_id}</td>
                <td>{displayOrderTime(order.ordered_at)}</td><td>{statusLabels[order.status]}</td><td>{order.currency}</td>
              </tr>)}</tbody>
            </table>
          </div>}
        <nav className="operations-pagination" aria-label="订单分页">
          <button className="quiet-button" disabled={loading || criteriaChanged || result.orders.offset === 0}
            onClick={() => void loadOperations(Math.max(0, result.orders.offset - result.orders.limit), result.productSales.offset)}>上一页</button>
          <span>第 {Math.floor(result.orders.offset / result.orders.limit) + 1} 页，本页 {result.orders.items.length} 条</span>
          <button className="quiet-button" disabled={loading || criteriaChanged || !result.orders.has_more}
            onClick={() => void loadOperations(result.orders.offset + result.orders.limit, result.productSales.offset)}>下一页</button>
        </nav>
        <h3>商品销量</h3>
        <p className="hint">按销量降序；使用订单成交单价计算，当前商品调价不改变历史销售额。</p>
        {result.productSales.items.length === 0 ? <p className="hint">这段日期没有商品销量记录。</p> :
          <div className="operations-table" role="region" aria-label="商品销量表" tabIndex={0}><table>
            <thead><tr><th>SKU / 商品名称</th><th>销量</th><th>订单数</th><th>币种</th><th>销售总额</th><th>退款</th><th>净销售额</th></tr></thead>
            <tbody>{result.productSales.items.map(item => <tr key={`${item.product_id}:${item.currency}`}>
              <td><strong>{item.sku}</strong><br />{item.title}</td><td>{item.units_sold}</td><td>{item.order_count}</td><td>{item.currency}</td>
              <td>{item.gross_sales}</td><td>{item.refund_amount}</td><td>{item.net_sales}</td>
            </tr>)}</tbody></table></div>}
        <nav className="operations-pagination" aria-label="商品销量分页">
          <button className="quiet-button" disabled={loading || criteriaChanged || result.productSales.offset === 0}
            onClick={() => void loadOperations(result.orders.offset, Math.max(0, result.productSales.offset - result.productSales.limit))}>上一页</button>
          <span>第 {Math.floor(result.productSales.offset / result.productSales.limit) + 1} 页</span>
          <button className="quiet-button" disabled={loading || criteriaChanged || !result.productSales.has_more}
            onClick={() => void loadOperations(result.orders.offset, result.productSales.offset + result.productSales.limit)}>下一页</button>
        </nav>
      </>}
    </section>
  )
}
