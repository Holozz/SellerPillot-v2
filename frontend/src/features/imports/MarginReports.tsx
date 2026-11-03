import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from '../../shared/api/client'
import type { Store } from '../../shared/types'

interface FxSet { id: number; source: string; source_revision: string }
interface FxRate { base_currency: string; effective_date: string; rate: string }
interface History { id: string; start_date: string; end_date: string; complete: boolean; known_margin_cny: string; created_at: string }
interface MarginJob { id: string; status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'INTERRUPTED' | 'STALE'; report_id?: string; error?: string; source_report_id?: string }
const jobLabels = { QUEUED: '排队中', RUNNING: '计算中', SUCCEEDED: '已完成', FAILED: '失败', INTERRUPTED: '已中断', STALE: '数据版本已变化' }
interface CostAllocation { source_batch_id?: string; procurement_batch_id: number; quantity: string | number; cost_batch_revision?: number; cost_currency: string; unit_purchase_cost: string; rate_date: string; rate: string | null; cost_cny_unrounded?: string }
interface Detail { store_id: number; external_line_id: string; event: string; original_amount: string; currency: string; original_purchase_cost: string; cost_currency: string; revenue_rate_date: string; revenue_rate: string | null; cost_rate_date: string; cost_rate: string | null; margin_cny_unrounded?: string; complete: boolean; source_row_id: number; procurement_batch_id: number; cost_allocations?: CostAllocation[] }
interface Result { complete: boolean; event_count: number; priced_event_count: number; sales_net_cny: string; refunds_cny: string; procurement_cost_cny: string; returned_cost_cny: string; known_margin_cny: string; gross_margin_cny: string | null; details: Detail[]; products: { internal_sku: string; known_margin_cny: string }[] }
interface Report { id: string; result: Result; input_manifest?: { start: string; end: string; store_ids: number[]; fx_rate_set: FxSet; rule_version: string; fx_policy?: string; fixed_rate_date?: string; revalued_from?: string } }
interface Comparison { before_margin_cny: string; after_margin_cny: string; margin_delta_cny: string; business_data_delta_cny: string; fx_delta_cny: string; component_deltas: Record<string, string>; changed_events: { event_key: string; margin_delta_cny: string; before: Detail | null; after: Detail | null }[] }

export default function MarginReports({ store, stores, onSessionExpired }: { store: Store; stores: Store[]; onSessionExpired: () => void }) {
  const base = `/v2/stores/${store.id}`
  const [sets, setSets] = useState<FxSet[]>([]), [rateSet, setRateSet] = useState(''), [rates, setRates] = useState<FxRate[]>([])
  const [history, setHistory] = useState<History[]>([]), [report, setReport] = useState<Report | null>(null)
  const [job, setJob] = useState<MarginJob | null>(null)
  const [selected, setSelected] = useState<number[]>([store.id])
  const [start, setStart] = useState(new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10)), [end, setEnd] = useState(new Date().toISOString().slice(0, 10))
  const [source, setSource] = useState(''), [revision, setRevision] = useState(''), [rateText, setRateText] = useState('')
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [detailPage, setDetailPage] = useState(0)
  const [details, setDetails] = useState<Detail[] | null>(null)
  const pending = useRef<AbortController | null>(null)
  const [fixed, setFixed] = useState(false), [fixedDate, setFixedDate] = useState('')
  const [baseline, setBaseline] = useState(''), [target, setTarget] = useState(''), [comparison, setComparison] = useState<Comparison | null>(null)
  async function refresh(signal: AbortSignal) {
    const [fx, reports] = await Promise.all([request<FxSet[]>(`${base}/fx-rate-sets`, { signal }), request<History[]>(`/v2/margin-reports?store_id=${store.id}`, { signal })])
    if (!signal.aborted) { setSets(fx); setHistory(reports) }
  }
  async function run(work: (signal: AbortSignal) => Promise<void>) {
    if (pending.current) return
    const controller = new AbortController(); pending.current = controller; setBusy(true); setError(''); setNotice('')
    try { await work(controller.signal) } catch (cause) {
      if (controller.signal.aborted) return
      if (cause instanceof ApiError && cause.status === 401) onSessionExpired()
      else setError(cause instanceof Error ? cause.message : '操作失败')
    } finally { if (pending.current === controller) { pending.current = null; if (!controller.signal.aborted) setBusy(false) } }
  }
  useEffect(() => { void run(refresh); return () => { pending.current?.abort(); pending.current = null } }, [store.id])
  async function open(id: string, signal: AbortSignal) {
    setReport(null); setDetailPage(0); setDetails(null)
    const saved = await request<Report>(`/v2/margin-reports/${id}/summary`, { signal }); if (!signal.aborted) setReport(saved)
  }
  async function openDetails(id: string, page: number, signal: AbortSignal) {
    const value = await request<{ rows: Detail[] }>(`/v2/margin-reports/${id}/details?page=${page}`, { signal })
    if (!signal.aborted) { setDetails(value.rows); setDetailPage(page) }
  }
  async function showJob(next: MarginJob, signal: AbortSignal) {
    if (signal.aborted) return
    setJob(next)
    if (next.report_id) {
      await refresh(signal)
      await open(next.report_id, signal)
      if (!signal.aborted && next.source_report_id) {
        setBaseline(next.source_report_id); setTarget(next.report_id); setComparison(null)
      }
    }
  }
  return <section className="source-history">
    <div className="section-heading"><div><h2>人民币销售毛利报表</h2><p>按付款日确认销售、退款确认日冲减收入，采购成本按批次入库日折算；仅确认退货入库时转回成本。</p></div><button className="quiet-button" disabled={busy} onClick={() => void run(refresh)}>刷新汇率与历史</button></div>
    {error && <p className="error-message" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}{busy && <p role="status">正在处理…</p>}
    <details><summary>导入历史汇率版本（仅店铺所有者）</summary>
      <p className="hint">每行填写“原币代码,有效日期,汇率”，例如 USD,2026-11-01,7.00，表示 1 USD = 7.00 CNY。请填写可追溯来源和修订号；同币种人民币无需填写。</p>
      <form className="java-query-form" onSubmit={event => { event.preventDefault(); void run(async signal => {
        const parsed = rateText.trim().split(/\r?\n/).map((line, index) => {
          const fields = line.split(',').map(value => value.trim()); if (fields.length !== 3 || fields.some(value => !value)) throw new Error(`第 ${index + 1} 行须填写币种、日期和汇率。`)
          return { base_currency: fields[0], effective_date: fields[1], rate: fields[2] }
        })
        const created = await request<{ id: number }>(`${base}/fx-rate-sets`, { method: 'POST', body: JSON.stringify({ source: source.trim(), source_revision: revision.trim(), rates: parsed }), signal })
        await refresh(signal); if (!signal.aborted) { setRateSet(String(created.id)); setRates(parsed); setNotice('汇率版本已保存。') }
      }) }}>
        <label>汇率来源<input required maxLength={100} value={source} disabled={busy} onChange={event => setSource(event.target.value)} /></label>
        <label>来源修订号<input required maxLength={64} value={revision} disabled={busy} onChange={event => setRevision(event.target.value)} /></label>
        <label>历史汇率行<textarea required rows={5} value={rateText} disabled={busy} onChange={event => setRateText(event.target.value)} /></label><button className="primary-button compact" disabled={busy}>保存汇率版本</button>
      </form>
    </details>
    <form onSubmit={event => { event.preventDefault(); void run(async signal => {
      const created = await request<MarginJob>('/v2/margin-report-jobs', { method: 'POST', body: JSON.stringify({ store_ids: selected, fx_rate_set_id: Number(rateSet), start, end, ...(fixed ? { fixed_rate_date: fixedDate } : {}) }), signal })
      await showJob(created, signal)
    }) }}>
      <fieldset disabled={busy}><legend>同一卖家的目标店铺</legend>{stores.filter(candidate => candidate.owner_id === store.owner_id).map(candidate => <label key={candidate.id} className="margin-store"><input type="checkbox" checked={selected.includes(candidate.id)} onChange={event => setSelected(previous => event.target.checked ? [...previous, candidate.id] : previous.filter(id => id !== candidate.id))} />{candidate.name} · {candidate.platform}</label>)}</fieldset>
      <div className="java-query-form">
        <label>开始日期（UTC）<input required type="date" value={start} disabled={busy} onChange={event => setStart(event.target.value)} /></label><label>结束日期（不含，UTC）<input required type="date" value={end} disabled={busy} onChange={event => setEnd(event.target.value)} /></label>
        <label>历史汇率版本<select aria-label="历史汇率版本" required value={rateSet} disabled={busy} onChange={event => { const id = event.target.value; setRateSet(id); setRates([]); if (id) void run(async signal => { const data = await request<{ rates: FxRate[] }>(`${base}/fx-rate-sets/${id}`, { signal }); if (!signal.aborted) setRates(data.rates) }) }}><option value="">请选择汇率版本</option>{sets.map(set => <option key={set.id} value={set.id}>{set.source} · {set.source_revision}（#{set.id}）</option>)}</select></label><button className="primary-button compact" disabled={busy || !selected.length || !rateSet}>生成并保存毛利报表</button>
      </div>
      <label className="margin-store"><input type="checkbox" checked={fixed} disabled={busy} onChange={event => setFixed(event.target.checked)} />使用固定日期汇率折算销售、退款与采购成本</label>
      {fixed && <label>固定汇率日期<input type="date" required value={fixedDate} disabled={busy} onChange={event => setFixedDate(event.target.value)} /></label>}
    </form>
    {job && <div>
      <p role="status">报表任务 {jobLabels[job.status]}{job.error ? `（${job.error}）` : ''}</p>
      <button className="quiet-button" disabled={busy} onClick={() => void run(async signal => {
        await showJob(await request<MarginJob>(`/v2/margin-report-jobs/${job.id}`, { signal }), signal)
      })}>刷新任务状态</button>
      {(job.status === 'FAILED' || job.status === 'INTERRUPTED') && <button className="quiet-button" disabled={busy} onClick={() => void run(async signal => {
        await showJob(await request<MarginJob>(`/v2/margin-report-jobs/${job.id}/retry`, { method: 'POST', signal }), signal)
      })}>重试报表任务</button>}
      {job.status === 'STALE' && <button className="quiet-button" disabled={busy} onClick={() => void run(async signal => {
        await showJob(await request<MarginJob>(`/v2/margin-report-jobs/${job.id}/regenerate`, { method: 'POST', signal }), signal)
      })}>按最新数据重新生成</button>}
    </div>}
    <p className="hint">日期区间左闭右开，最多 20 家店、366 天、10000 条销售/退款事件。当前只匹配业务日期当天汇率；缺失记录保留原币并不计入已定价小计。</p>
    {rates.length > 0 && <details><summary>查看当前所选汇率依据（{rates.length} 条）</summary><div className="java-table-scroll"><table><thead><tr><th>原币</th><th>有效日</th><th>1 单位原币折人民币</th></tr></thead><tbody>{rates.map(rate => <tr key={`${rate.base_currency}:${rate.effective_date}`}><td>{rate.base_currency}</td><td>{rate.effective_date}</td><td>{rate.rate}</td></tr>)}</tbody></table></div></details>}
    <h3>包含当前店铺的最近 50 份可访问报表</h3><div className="java-table-scroll"><table><thead><tr><th>生成时间（UTC）</th><th>期间</th><th>状态</th><th>人民币毛利 / 已定价小计</th><th>查看</th></tr></thead><tbody>{history.map(item => <tr key={item.id}><td>{item.created_at}</td><td>{item.start_date} 至 {item.end_date}（不含）</td><td>{item.complete ? '完整定价' : '汇率不完整'}</td><td>{item.known_margin_cny}</td><td><button className="quiet-button" disabled={busy} onClick={() => void run(signal => open(item.id, signal))}>打开报表</button></td></tr>)}</tbody></table></div>
    {!history.length && <p className="hint">暂无可访问的已保存报表。</p>}
    <h3>报表差额对比</h3><p className="hint">须为相同店铺、期间和口径。固定汇率报表还须共用同一汇率版本及日期；缺汇率时不分解完整毛利。</p>
    <form className="java-query-form" onSubmit={event => { event.preventDefault(); void run(async signal => { setComparison(null); const data = await request<Comparison>('/v2/margin-reports/compare', { method: 'POST', body: JSON.stringify({ baseline_id: baseline, target_id: target }), signal }); if (!signal.aborted) setComparison(data) }) }}>
      <label>基准报表<select aria-label="基准报表" required disabled={busy} value={baseline} onChange={event => { setBaseline(event.target.value); setComparison(null) }}><option value="">请选择</option>{history.map(item => <option key={item.id} value={item.id}>{item.id} · {item.start_date} · {item.known_margin_cny} 元</option>)}</select></label>
      <label>目标报表<select aria-label="目标报表" required disabled={busy} value={target} onChange={event => { setTarget(event.target.value); setComparison(null) }}><option value="">请选择</option>{history.map(item => <option key={item.id} value={item.id}>{item.id} · {item.start_date} · {item.known_margin_cny} 元</option>)}</select></label><button className="primary-button compact" disabled={busy || !baseline || !target}>计算报表差额</button>
    </form>
    {comparison && <section><p role="status">毛利差额 {comparison.margin_delta_cny} 元 = 业务数据变化 {comparison.business_data_delta_cny} 元 + 汇率变化 {comparison.fx_delta_cny} 元。</p><p className="hint">固定分解顺序：先用基准报表汇率计算目标事实，再切换目标汇率。此处为金额分解，不代表对业务原因的推断。</p>
      <div className="java-table-scroll"><table><thead><tr><th>销售净额变化</th><th>退款变化</th><th>采购成本变化</th><th>退货成本转回变化</th></tr></thead><tbody><tr>{['sales_net_cny', 'refunds_cny', 'procurement_cost_cny', 'returned_cost_cny'].map(field => <td key={field}>{comparison.component_deltas[field]}</td>)}</tr></tbody></table></div>
      <details><summary>查看变化事件（展示前 50 / 共 {comparison.changed_events.length} 条）</summary>{comparison.changed_events.slice(0, 50).map(event => <p key={event.event_key}>{event.event_key} · 毛利差额 {event.margin_delta_cny} 元 · 来源行 {event.before?.source_row_id ?? '无'} → {event.after?.source_row_id ?? '无'} · 批次修订 {event.before?.procurement_batch_id ?? '无'} → {event.after?.procurement_batch_id ?? '无'}</p>)}</details>
    </section>}
    {report && <section><h3>报表 {report.id}</h3>
      {report.input_manifest && <p>期间：{report.input_manifest.start} 至 {report.input_manifest.end}（不含）· 店铺：{report.input_manifest.store_ids.join('、')} · 汇率：{report.input_manifest.fx_rate_set.source} / {report.input_manifest.fx_rate_set.source_revision} · {report.input_manifest.fixed_rate_date ? `固定日期 ${report.input_manifest.fixed_rate_date}` : '各业务日期汇率'}</p>}
      {report.input_manifest?.revalued_from && <p>采购成本重估来源报表：{report.input_manifest.revalued_from}。销售、退款和汇率沿用来源报表快照。</p>}
      <p role="status">{report.result.complete ? `销售毛利：${report.result.gross_margin_cny} 元` : `汇率不完整，已定价毛利小计：${report.result.known_margin_cny} 元；完整销售毛利暂不可用。`}</p>
      <p>已定价事件 {report.result.priced_event_count} / {report.result.event_count}。覆盖范围仅指本系统已发布的销售和退款记录。</p>
      <div className="java-table-scroll"><table><thead><tr><th>销售净额（扣折扣）</th><th>退款</th><th>已售采购成本</th><th>退货成本转回</th></tr></thead><tbody><tr><td>{report.result.sales_net_cny}</td><td>{report.result.refunds_cny}</td><td>{report.result.procurement_cost_cny}</td><td>{report.result.returned_cost_cny}</td></tr></tbody></table></div>
      {!report.result.complete && <p className="hint">上述各项仅统计完整定价事件；下方未定价明细可核对原币金额和缺失日期。</p>}
      <button className="quiet-button" disabled={busy} onClick={() => void run(async signal => {
        const value = await request<Result>(`/v2/margin-reports/${report.id}/recompute`, { method: 'POST', signal })
        if (!signal.aborted) { setReport({ ...report, result: value }); setNotice('已使用原报表输入重新计算，保存的报表未被覆盖。') }
      })}>用原输入复算</button>
      <button className="quiet-button" disabled={busy} onClick={() => {
        if (!window.confirm('确认按当前采购批次修订生成成本重估报表？销售、退款和汇率沿用此报表快照；旧报表及销售记录保持不变。仅店铺所有者可执行。')) return
        void run(async signal => {
          const created = await request<MarginJob>(`/v2/margin-reports/${report.id}/revalue-costs`, { method: 'POST', signal })
          await showJob(created, signal)
        })
      }}>按最新采购成本生成新报表</button>
      <h4>统一商品毛利排序（展示前 50 项）</h4><div className="java-table-scroll"><table><thead><tr><th>统一 SKU</th><th>已定价毛利（元）</th></tr></thead><tbody>{report.result.products.slice(0, 50).map(product => <tr key={product.internal_sku}><td>{product.internal_sku}</td><td>{product.known_margin_cny}</td></tr>)}</tbody></table></div>
      {details === null ? <button className="quiet-button" disabled={busy || report.result.event_count === 0} onClick={() => void run(signal => openDetails(report.id, 0, signal))}>加载折算明细</button> : <>
      <h4>折算明细（第 {detailPage + 1} 页）</h4><div className="java-table-scroll"><table><thead><tr><th>店铺 / 来源行</th><th>业务明细</th><th>事件</th><th>原币收入/退款</th><th>原币采购成本</th><th>收入/退款汇率日期及汇率</th><th>采购汇率日期及汇率</th><th>状态 / 毛利影响（未舍入）</th></tr></thead><tbody>{details.map((detail, index) => <tr key={index}><td>{detail.store_id} / #{detail.source_row_id}</td><td>{detail.external_line_id}</td><td>{detail.event === 'sales' ? '销售' : '退款/退货'}</td><td>{detail.original_amount} {detail.currency}</td><td>{detail.original_purchase_cost} {detail.cost_currency}</td><td>{detail.revenue_rate_date} / {detail.revenue_rate ?? '缺失'}</td><td>{detail.cost_rate_date === 'MULTIPLE' ? '按下方批次分配明细折算' : `${detail.cost_rate_date} / ${detail.cost_rate ?? '缺失'}`}</td><td>{detail.complete ? detail.margin_cny_unrounded : '未定价'}</td></tr>)}</tbody></table></div>
      {details.filter(detail => detail.cost_allocations?.length).map(detail => <details key={`${detail.event}:${detail.source_row_id}`}>
        <summary>{detail.external_line_id} · 来源行 #{detail.source_row_id} · 采购批次分配（{detail.cost_allocations!.length} 笔）</summary>
        <div className="java-table-scroll"><table><thead><tr><th>采购批次 / 修订</th><th>数量</th><th>单位采购成本</th><th>成本汇率日期 / 汇率</th><th>成本人民币（未舍入）</th></tr></thead><tbody>{detail.cost_allocations!.map(part => <tr key={part.procurement_batch_id}><td>{part.source_batch_id ?? `#${part.procurement_batch_id}`} / {part.cost_batch_revision ?? '历史版本'}</td><td>{part.quantity}</td><td>{part.unit_purchase_cost} {part.cost_currency}</td><td>{part.rate_date} / {part.rate ?? '缺失'}</td><td>{part.cost_cny_unrounded ?? '未定价'}</td></tr>)}</tbody></table></div>
      </details>)}
      <button className="quiet-button" disabled={busy || detailPage === 0} onClick={() => void run(signal => openDetails(report.id, detailPage - 1, signal))}>上一页明细</button><button className="quiet-button" disabled={busy || (detailPage + 1) * 50 >= report.result.event_count} onClick={() => void run(signal => openDetails(report.id, detailPage + 1, signal))}>下一页明细</button>
      </>}
    </section>}
  </section>
}
