import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, importCsv } from '../../shared/api'
import type { CsvIssue, ImportKind, ImportResult, Store } from '../../shared/api'

interface Props {
  store: Store
  onSessionExpired: () => void
}

const MAX_FILE_BYTES = 5 * 1024 * 1024
// Record要求两种ImportKind都对应一个字符串；HEADERS[kind]取出当前模板的表头。
const HEADERS: Record<ImportKind, string> = {
  orders: 'external_order_id,ordered_at,status,currency',
  'order-items': 'external_order_id,line_no,sku,quantity,unit_price,refund_amount',
}

// 这里只做文件名和体积的快速提示；编码、CSV内容及业务关联仍由后端验证。
function validateCsvFile(file: File): string {
  if (!file.name.toLowerCase().endsWith('.csv')) return '请选择扩展名为 .csv 的文件。'
  if (file.size > MAX_FILE_BYTES) return '文件不能超过 5 MiB。'
  return ''
}

export default function CsvImports({ store, onSessionExpired }: Props) {
  const [kind, setKind] = useState<ImportKind>('orders')
  const [file, setFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  const [result, setResult] = useState<ImportResult | null>(null)
  const [error, setError] = useState('')
  const [issues, setIssues] = useState<CsvIssue[]>([])
  const pending = useRef<AbortController | null>(null)
  // 根据当前选中的文件计算，不必再保存一份容易不同步的校验状态。
  const validationError = file ? validateCsvFile(file) : ''

  // 切店或退出时取消浏览器等待；已发给后端的导入仍可能完成。
  useEffect(() => () => pending.current?.abort(), [])

  function handleKindChange(nextKind: ImportKind) {
    if (pending.current) return
    setKind(nextKind)
    setFile(null)
    // 切换模板后清除旧结果，避免把订单导入回执看成明细导入结果。
    setResult(null)
    setError('')
    setIssues([])
  }

  function handleFileChange(nextFile: File | null) {
    if (pending.current) return
    setFile(nextFile)
    // 文件一换，旧成功回执和错误明细就不再对应当前文件。
    setResult(null)
    setError('')
    setIssues([])
  }

  async function handleUpload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    // ref立即生效，阻止连续提交；uploading负责随后禁用控件和显示进度。
    if (pending.current) return
    if (!file) { setError('请先选择CSV文件。'); return }
    const invalid = validateCsvFile(file)
    if (invalid) { setError(invalid); return }

    const controller = new AbortController()
    pending.current = controller
    setUploading(true)
    // 新请求开始后不再展示旧回执，避免让用户误以为本次已经成功。
    setResult(null)
    setError('')
    setIssues([])
    try {
      const imported = await importCsv(store.id, kind, file, controller.signal)
      // 用户可能在等待中切到了另一家店，旧结果不能填回新页面。
      if (controller.signal.aborted) return
      setResult(imported)
      // 保留文件选择，便于用户重复上传验证后端内容去重。
    } catch (cause) {
      if (controller.signal.aborted) return
      if (cause instanceof ApiError && cause.status === 401) {
        onSessionExpired()
      } else {
        setError(cause instanceof Error ? cause.message : '导入请求失败，请稍后重试。')
        // 普通网络错误没有行级明细；后端CSV校验错误则逐条展示。
        setIssues(cause instanceof ApiError ? cause.issues : [])
      }
    } finally {
      // 只释放本次持有的请求引用；已取消的旧组件不再更新显示状态。
      if (pending.current === controller) pending.current = null
      if (!controller.signal.aborted) setUploading(false)
    }
  }

  return (
    <section className="csv-panel" aria-label="经营数据导入">
      <div className="chat-heading"><div><h2>经营数据导入</h2>
        <p>当前店铺：{store.name}。先导入订单，再导入对应明细。</p></div></div>
      <p className="hint">固定CSV格式，UTF-8编码；每个文件最多5 MiB、10000条数据记录。导入明细前需已有对应商品。</p>
      <form className="csv-form" onSubmit={handleUpload}>
        <label htmlFor="import-kind">导入内容</label>
        <select id="import-kind" value={kind} disabled={uploading}
          onChange={(event) => handleKindChange(event.target.value as ImportKind)}>
          <option value="orders">订单</option><option value="order-items">订单明细</option>
        </select>
        <p className="csv-header-label">表头及顺序</p><pre className="csv-header">{HEADERS[kind]}</pre>
        <p className="hint">{kind === 'orders'
          ? '下单时间需带时区；状态为pending、paid、shipped或cancelled，币种使用三个大写字母。'
          : '明细必须关联本店铺的订单与SKU，币种一致；退款不能超过数量×成交单价。'}</p>
        <label htmlFor="csv-file">CSV文件</label>
        {/* 类型变化时重建文件输入框，让浏览器显示的文件名也随file状态一起清空。 */}
        <input key={kind} id="csv-file" type="file" accept=".csv,text/csv" required disabled={uploading}
          onChange={(event) => handleFileChange(event.target.files?.[0] ?? null)} />
        {file && <p className="hint">已选择：{file.name}（{(file.size / 1024).toFixed(1)} KiB）</p>}
        <button className="primary-button csv-submit" type="submit"
          disabled={uploading || !file || Boolean(validationError)}>
          {uploading ? '正在导入…' : '上传并导入'}
        </button>
      </form>

      {(validationError || error) && <p className="error-message" role="alert">{validationError || error}</p>}
      {issues.length > 0 && <div className="csv-errors" role="region" aria-label="导入错误明细">
        {issues.length > 50 && <p className="hint csv-error-count">共{issues.length}项错误，当前展示前50项。</p>}
        <table><thead><tr><th>位置</th><th>字段</th><th>原因</th></tr></thead>
          {/* 大文件可能有很多错误，仅渲染前50项，完整数量仍保留用于提示。 */}
          <tbody>{issues.slice(0, 50).map((issue, index) => (
            <tr key={index}><td>{issue.row === null ? '文件' : `第${issue.row}行`}</td>
              <td>{issue.field}</td><td>{issue.reason}</td></tr>
          ))}</tbody></table>
      </div>}

      {result && <section className="csv-result" aria-label="导入结果" role="status">
        <h3>{result.duplicate ? '文件已导入，本次未重复写入' : '导入成功'}</h3>
        <dl><div><dt>导入记录</dt><dd>#{result.import_job_id}</dd></div>
          <div><dt>文件数据行数</dt><dd>{result.row_count}</dd></div>
          <div><dt>首次导入版本</dt><dd>{result.data_version}</dd></div></dl>
        {result.duplicate && <p>返回本文件首次成功导入的记录和版本，不代表店铺当前最新版本。</p>}
      </section>}
      <p className="hint csv-note">两个文件分别提交，单个文件失败会整体回滚；已经成功导入的另一份文件会保留。
        网络中断后可重传原文件核实，后端按文件内容去重。导入后可重新提问，查询更新后的经营数据。</p>
    </section>
  )
}
