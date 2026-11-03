import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from '../../shared/api/client'
import type { Store } from '../../shared/types'

const labels: Record<string, string> = { 'procurement-batches': '采购批次', orders: '订单', 'order-items': '销售明细', refunds: '退款/退货' }
const headers: Record<string, Record<string, string>> = {
  'market-a': {
    orders: 'platform_code,store_key,external_order_id,ordered_at,paid_at,status,currency',
    'order-items': 'platform_code,store_key,external_line_id,external_order_id,external_sku,quantity,unit_price,discount_amount,currency,batch_allocations',
    refunds: 'platform_code,store_key,external_refund_id,external_line_id,confirmed_at,refund_amount,currency,returned_quantity,restocked,return_allocations',
    'procurement-batches': 'platform_code,store_key,source_batch_id,external_sku,received_at,quantity,unit_purchase_cost,currency',
  },
  'market-b': {
    orders: 'platform_code,store_key,external_order_id,order_timestamp,payment_timestamp,order_state,order_currency',
    'order-items': 'platform_code,store_key,line_key,order_number,merchant_sku,units,unit_sale_price,line_discount,price_currency,batch_allocations',
    refunds: 'platform_code,store_key,refund_id,line_key,confirmation_timestamp,refund_value,refund_currency,returned_units,restocked,return_allocations',
    'procurement-batches': 'platform_code,store_key,purchase_lot,merchant_sku,receipt_timestamp,received_units,cost_per_unit,cost_currency',
  },
}
interface Receipt { import_id: number; row_count: number; duplicate: boolean; status: string }
export default function SourceUpload({ store, onSessionExpired }: { store: Store; onSessionExpired: () => void }) {
  const [kind, setKind] = useState('procurement-batches'), [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [conflict, setConflict] = useState(false)
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const pending = useRef<AbortController | null>(null)
  useEffect(() => () => pending.current?.abort(), [])
  function reset() { setError(''); setConflict(false); setReceipt(null) }
  async function upload(confirm = false) {
    if (pending.current || !file) return
    if (!file.name.toLowerCase().endsWith('.csv') || !file.size || file.size > 5 * 1024 * 1024) { setError('请选择非空的 CSV 文件，最大 5 MiB。'); return }
    if (confirm && !window.confirm(`确认将“${file.name}”中与当前数据不同的${labels[kind]}追加为新修订？店铺：${store.name}。旧修订将保留。`)) return
    const controller = new AbortController(); pending.current = controller; setBusy(true); reset()
    const body = new FormData(); body.append('file', file)
    try {
      const result = await request<Receipt>(`/v2/stores/${store.id}/source-imports/${kind}${confirm ? '?confirm_conflicts=true' : ''}`, { method: 'POST', body, signal: controller.signal })
      if (!controller.signal.aborted) setReceipt(result)
    } catch (cause) {
      if (controller.signal.aborted) return
      if (cause instanceof ApiError && cause.status === 401) onSessionExpired()
      else {
        const canConfirm = cause instanceof ApiError && cause.status === 409 && cause.message.includes('confirm_conflicts=true')
        setConflict(canConfirm); setError(canConfirm ? '文件中的部分业务记录与当前内容不同。请核对文件后决定是否追加修订。' : cause instanceof Error ? cause.message : '导入失败')
      }
    } finally { if (pending.current === controller) { pending.current = null; if (!controller.signal.aborted) setBusy(false) } }
  }
  function downloadTemplate() {
    const url = URL.createObjectURL(new Blob([headers[store.platform][kind] + '\n'], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a'); link.href = url; link.download = `${store.platform}-${kind}.csv`; link.click(); URL.revokeObjectURL(url)
  }
  return <section>
    <h2>统一平台文件导入</h2><p>当前平台：{store.platform} · 来源店铺标识：{store.source_store_key}</p>
    {!headers[store.platform] ? <p className="hint">当前平台尚未配置统一文件格式，请使用现有订单导入入口。</p> : <>
      <p className="hint">先在“统一商品映射”关联来源 SKU，再依次导入采购批次、订单、销售明细和退款。文件中的平台和店铺标识须与当前店铺一致。仅所有者可导入。</p>
      <form className="java-query-form" onSubmit={event => { event.preventDefault(); void upload() }}>
        <label>统一导入内容<select value={kind} disabled={busy} onChange={event => { setKind(event.target.value); setFile(null); reset() }}>{Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label>平台 CSV 文件<input key={kind} type="file" accept=".csv,text/csv" disabled={busy} onChange={event => { setFile(event.target.files?.[0] ?? null); reset() }} /></label>
        <button className="quiet-button" type="button" disabled={busy} onClick={downloadTemplate}>下载当前表头</button><button className="primary-button compact" disabled={busy || !file}>{busy ? '正在导入…' : '导入平台文件'}</button>
      </form>
      {(kind === 'order-items' || kind === 'refunds') && <details><summary>批次分配填写说明</summary>
        <p>销售明细的 batch_allocations 填写各采购批次及分配数量，合计须等于销售数量。退货的 return_allocations 须指向原销售批次，合计等于退货数量；同一批次累计退货不得超过原销售分配量。</p>
        <p>分配内容为 JSON 数组，例如：</p><pre>{'[{"source_batch_id":"LOT-A","quantity":1},{"source_batch_id":"LOT-B","quantity":2}]'}</pre>
        <p>作为 CSV 单元格时，用双引号包裹，并将内部双引号写两次。兼容旧单批次销售文件；多批次销售的退货必须明确分配，纯退款可填空数组。</p>
      </details>}
      {error && <p role="alert" className="error-message">{error}</p>}
      {conflict && <button className="primary-button compact" disabled={busy} onClick={() => void upload(true)}>核对后确认追加修订</button>}
      {receipt && <div role="status"><h3>{receipt.duplicate ? '文件已导入，无需重复处理' : '平台文件导入成功'}</h3><p>批次 #{receipt.import_id} · {labels[kind]} · {receipt.row_count} 行。可在“来源与修订”核对原始记录及业务版本。</p></div>}
    </>}
  </section>
}
