import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from '../../shared/api/client'

type Row = Record<string, unknown>
interface Page { rows: Row[]; next_after: number }
const kinds: Record<string, string> = { orders: '订单', 'order-items': '销售明细', refunds: '退款/退货', 'procurement-batches': '采购批次' }

export default function SourceHistory({ storeId, onSessionExpired }: { storeId: number; onSessionExpired: () => void }) {
  const base = `/v2/stores/${storeId}`
  const [batches, setBatches] = useState<Row[]>([])
  const [cursor, setCursor] = useState(0)
  const [more, setMore] = useState(false)
  const [rows, setRows] = useState<Row[]>([])
  const [selected, setSelected] = useState<number | null>(null)
  const [rowCursor, setRowCursor] = useState(0)
  const [moreRows, setMoreRows] = useState(false)
  const [kind, setKind] = useState('orders')
  const [key, setKey] = useState('')
  const [revisions, setRevisions] = useState<Row[]>([])
  const [revisionCursor, setRevisionCursor] = useState(0)
  const [moreRevisions, setMoreRevisions] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  async function run(work: (signal: AbortSignal) => Promise<void>) {
    pending.current?.abort()
    const controller = new AbortController(); pending.current = controller; setBusy(true); setError('')
    try { await work(controller.signal) } catch (cause) {
      if (controller.signal.aborted) return
      if (cause instanceof ApiError && cause.status === 401) onSessionExpired()
      else setError(cause instanceof Error ? cause.message : '查询失败')
    } finally { if (pending.current === controller) { pending.current = null; setBusy(false) } }
  }
  function loadBatches(after = 0) { return run(async signal => {
    const page = await request<Page>(`${base}/source-imports?after=${after}`, { signal })
    if (signal.aborted) return
    setBatches(previous => after ? [...previous, ...page.rows] : page.rows); setCursor(page.next_after); setMore(page.rows.length === 50)
  }) }
  function loadRows(batch: number, after = 0) { return run(async signal => {
    const page = await request<Page>(`${base}/source-imports/${batch}/rows?after=${after}`, { signal })
    if (signal.aborted) return
    setSelected(batch); setRows(previous => after ? [...previous, ...page.rows] : page.rows); setRowCursor(page.next_after); setMoreRows(page.rows.length === 50)
  }) }
  function loadRevisions(after = 0) { return run(async signal => {
    const page = await request<Page>(`${base}/source-revisions/${kind}?key=${encodeURIComponent(key.trim())}&after=${after}`, { signal })
    if (signal.aborted) return
    setRevisions(previous => after ? [...previous, ...page.rows] : page.rows); setRevisionCursor(page.next_after); setMoreRevisions(page.rows.length === 50)
  }) }
  useEffect(() => { void loadBatches(); return () => pending.current?.abort() }, [storeId])
  return <div className="source-history">
    <div className="section-heading"><div><h2>导入历史与来源核对</h2><p>查看已发布文件、原始行和业务修订；修订保留当时采用的来源与金额。</p></div><button className="quiet-button" disabled={busy} onClick={() => void loadBatches()}>刷新批次</button></div>
    {error && <p role="alert" className="error-message">{error}</p>}
    {busy && <p role="status" className="hint">正在查询…</p>}
    <div className="java-table-scroll"><table><thead><tr><th>批次</th><th>类型</th><th>记录数</th><th>发布时间（UTC）</th><th>来源行</th></tr></thead><tbody>{batches.map(batch => <tr key={String(batch.id)}><td>#{String(batch.id)}</td><td>{kinds[String(batch.kind)] ?? String(batch.kind)}</td><td>{String(batch.source_row_count)}</td><td>{String(batch.published_at ?? '—')}</td><td><button className="quiet-button" disabled={busy} onClick={() => void loadRows(Number(batch.id))}>查看</button></td></tr>)}</tbody></table></div>
    {!batches.length && !busy && <p className="empty-state">暂无统一平台文件导入记录。</p>}
    {more && <button className="quiet-button" disabled={busy} onClick={() => void loadBatches(cursor)}>加载更多批次</button>}
    {selected !== null && <section><h3>批次 #{selected} 的来源行</h3>{!rows.length && <p className="hint">该文件没有数据行。</p>}{rows.map(row => <details key={String(row.id)}><summary>第 {String(row.source_row_number)} 行 · {String(row.source_record_key)}</summary><p>原始字段</p><pre>{JSON.stringify(row.raw_fields, null, 2)}</pre><p>统一字段</p><pre>{JSON.stringify(row.normalized_fields, null, 2)}</pre></details>)}{moreRows && <button className="quiet-button" disabled={busy} onClick={() => void loadRows(selected, rowCursor)}>加载更多来源行</button>}</section>}
    <h3>按业务编号查询修订</h3><form className="java-query-form" onSubmit={event => { event.preventDefault(); void loadRevisions() }}>
      <label>类型<select value={kind} disabled={busy} onChange={event => { setKind(event.target.value); setRevisions([]); setMoreRevisions(false) }}>{Object.entries(kinds).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label>来源业务编号<input required maxLength={128} value={key} disabled={busy} onChange={event => { setKey(event.target.value); setRevisions([]); setMoreRevisions(false) }} /></label><button className="primary-button compact" disabled={busy || !key.trim()}>查询修订</button>
    </form>{revisions.map(revision => <details key={String(revision.id)}><summary>修订 {String(revision.revision ?? revision.batch_revision)} · 来源批次 #{String(revision.batch_id)} / 第 {String(revision.source_row_number)} 行</summary><pre>{JSON.stringify(revision, null, 2)}</pre></details>)}{moreRevisions && <button className="quiet-button" disabled={busy} onClick={() => void loadRevisions(revisionCursor)}>加载更多修订</button>}
  </div>
}
