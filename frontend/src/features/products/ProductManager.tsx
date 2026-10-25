import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, createProduct, deleteProduct, getLowStockProducts, getProduct, getProducts, updateProduct } from '../../shared/api'
import type { Product, Store } from '../../shared/types'

interface Props { store: Store; onSessionExpired: () => void }
type Mode = 'products' | 'low-stock'
interface Editor { sku: string; title: string; price: string; currency: string; stock: string }
const emptyEditor = (): Editor => ({ sku: '', title: '', price: '0.00', currency: 'USD', stock: '0' })

export default function ProductManager({ store, onSessionExpired }: Props) {
  const [mode, setMode] = useState<Mode>('products')
  const [threshold, setThreshold] = useState('10')
  const [result, setResult] = useState<{ mode: Mode; threshold: number; items: Product[] } | null>(null)
  const [editor, setEditor] = useState<Editor | null>(null)
  const [editingSku, setEditingSku] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<Product | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const pending = useRef<AbortController | null>(null)
  const changed = result && (mode !== result.mode || (mode === 'low-stock' && (threshold === '' || Number(threshold) !== result.threshold)))

  async function runAction(operation: (signal: AbortSignal) => Promise<void>) {
    // 同步锁覆盖查询和写入，防止连续点击提交两次；busy负责页面渲染。
    if (pending.current) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setNotice('')
    try { await operation(controller.signal) }
    catch (cause) {
      if (controller.signal.aborted) return
      if (cause instanceof ApiError && cause.status === 401) onSessionExpired()
      else setError(cause instanceof Error ? cause.message : '商品操作失败，请重试。')
    } finally {
      if (pending.current === controller) { pending.current = null; setBusy(false) }
    }
  }

  async function loadProducts(signal: AbortSignal, queryMode = mode, cutoff = Number(threshold)) {
    const items = queryMode === 'products' ? await getProducts(store.id, signal) : await getLowStockProducts(store.id, cutoff, signal)
    if (!signal.aborted) setResult({ mode: queryMode, threshold: cutoff, items })
  }

  useEffect(() => {
    void runAction(signal => loadProducts(signal, 'products', 10))
    return () => { pending.current?.abort(); pending.current = null }
  }, [store.id])

  function handleQuery(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (mode === 'low-stock' && (threshold.trim() === '' || !Number.isSafeInteger(Number(threshold)) || Number(threshold) < 0)) {
      setError('阈值必须是有效的非负整数。'); return
    }
    void runAction(signal => loadProducts(signal))
  }

  function handleEdit(sku: string) {
    void runAction(async signal => {
      // 用详情接口读取最新值，避免直接把列表加载时的旧库存填入编辑表单。
      const product = await getProduct(sku, signal, store.id)
      if (signal.aborted) return
      setEditingSku(product.sku)
      setEditor({ sku: product.sku, title: product.title, price: product.price, currency: product.currency, stock: String(product.stock) })
      setDeleting(null)
    })
  }

  function handleSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!editor) return
    const stock = Number(editor.stock)
    if (!Number.isInteger(stock) || stock < 0 || stock > 2147483647) { setError('库存必须是0至2147483647的整数。'); return }
    // 金额以字符串发送，限制为DECIMAL(18,2)可保存的范围，不先转浮点数。
    if (!/^\d{1,16}(\.\d{1,2})?$/.test(editor.price)) { setError('价格须为非负金额，最多2位小数和16位整数。'); return }
    const data = { title: editor.title.trim(), price: editor.price, currency: editor.currency, stock }
    void runAction(async signal => {
      const saved = editingSku ? await updateProduct(editingSku, data, signal, store.id)
        : await createProduct({ ...data, sku: editor.sku.trim(), store_id: store.id }, signal)
      if (signal.aborted) return
      // 写入成功就结束编辑，随后刷新失败时要明确提示，不能让用户误以为需再次创建。
      setEditor(null); setEditingSku(null); setNotice(`商品 ${saved.sku} 已保存。`)
      await refreshAfterWrite(signal, '商品已保存')
    })
  }

  async function refreshAfterWrite(signal: AbortSignal, message: string) {
    try { await loadProducts(signal) }
    catch (cause) {
      if (signal.aborted) return
      setResult(null)
      if (cause instanceof ApiError && cause.status === 401) throw cause
      if (!signal.aborted) setError(`${message}，但列表刷新失败，请点击查询重试。`)
    }
  }

  function handleDelete() {
    if (!deleting) return
    void runAction(async signal => {
      await deleteProduct(deleting.sku, signal, store.id)
      if (signal.aborted) return
      setNotice(`商品 ${deleting.sku} 已删除。`); setDeleting(null)
      await refreshAfterWrite(signal, '商品已删除')
    })
  }

  function setField(field: keyof Editor, value: string) {
    // 复制原对象，仅替换指定字段；不直接修改React保存的对象。
    setEditor(current => current ? { ...current, [field]: value } : null)
  }

  return <section className="inventory-panel" aria-labelledby="inventory-heading" aria-busy={busy}>
    <div className="chat-heading"><div><h2 id="inventory-heading">商品管理</h2><p>{store.name} / 商品资料与库存</p></div>
      <button className="primary-button compact" disabled={busy} onClick={() => {
        setEditor(emptyEditor()); setEditingSku(null); setDeleting(null); setError(''); setNotice('')
      }}>新增商品</button></div>
    {error && <p className="error-message" role="alert">{error}</p>}
    {notice && <p className="success-message" role="status">{notice}</p>}
    {editor && <form className="editor-form product-editor" aria-label={editingSku ? '编辑商品' : '新增商品'} onSubmit={handleSave}>
      <h3>{editingSku ? `编辑 ${editingSku}` : '新增商品'}</h3>
      <label>SKU<input required minLength={3} maxLength={30} pattern="[A-Za-z0-9-]+" disabled={busy || editingSku !== null}
        value={editor.sku} onChange={event => setField('sku', event.target.value)} /></label>
      <label className="wide-field">商品名称<input required maxLength={200} disabled={busy} value={editor.title} onChange={event => setField('title', event.target.value)} /></label>
      <label>单价<input required inputMode="decimal" disabled={busy} value={editor.price} onChange={event => setField('price', event.target.value)} /></label>
      <label>币种<input required pattern="[A-Z]{3}" maxLength={3} disabled={busy} value={editor.currency} onChange={event => setField('currency', event.target.value.toUpperCase())} /></label>
      <label>库存<input type="number" required min={0} max={2147483647} step={1} disabled={busy} value={editor.stock} onChange={event => setField('stock', event.target.value)} /></label>
      <div className="form-actions"><button className="primary-button compact" disabled={busy || !editor.title.trim()}>{busy ? '正在保存…' : '保存商品'}</button>
        <button className="quiet-button" type="button" disabled={busy} onClick={() => setEditor(null)}>取消编辑</button></div>
    </form>}
    {deleting && <section className="delete-confirm" aria-label="删除商品确认">
      <h3>删除 {deleting.sku}？</h3><p>将删除“{deleting.title}”。已有订单明细或标题草稿关联的商品无法删除。</p>
      <button className="danger-button" disabled={busy} onClick={handleDelete}>确认删除商品</button>
      <button className="quiet-button" disabled={busy} onClick={() => setDeleting(null)}>取消删除</button>
    </section>}
    <form className="inventory-form" onSubmit={handleQuery}>
      <label>查询范围<select value={mode} disabled={busy} onChange={event => setMode(event.target.value as Mode)}><option value="products">商品列表</option><option value="low-stock">低库存</option></select></label>
      {mode === 'low-stock' && <label>低库存阈值<input type="number" required min={0} step={1} max={Number.MAX_SAFE_INTEGER} value={threshold} disabled={busy} onChange={event => setThreshold(event.target.value)} /></label>}
      <button className="quiet-button" disabled={busy}>{busy ? '正在处理…' : '查询商品'}</button>
    </form>
    <p className="hint">低库存为库存严格小于阈值。最多展示100条；商品列表按编号、低库存按库存升序。</p>
    {changed && <p className="inventory-warning">条件已修改，请查询更新结果。</p>}
    {result && <><p className="inventory-context">{result.mode === 'products' ? '商品列表' : `库存小于 ${result.threshold}`} / 本次 {result.items.length} 条</p>
      {result.items.length === 100 && <p className="inventory-warning">已达100条上限，可能还有未展示的商品。</p>}
      {result.items.length === 0 ? <p className="empty-state">{result.mode === 'products' ? '暂无商品，点击新增商品开始。' : '没有符合阈值的商品。'}</p> :
        <div className="inventory-table" role="region" aria-label="商品列表" tabIndex={0}><table><thead><tr><th>SKU</th><th>商品名称</th><th>库存</th><th>单价</th><th>币种</th><th>操作</th></tr></thead>
          <tbody>{result.items.map(product => <tr key={product.id}><td>{product.sku}</td><td className="inventory-title">{product.title}</td><td className={product.stock === 0 ? 'inventory-zero' : undefined}>{product.stock}</td><td>{product.price}</td><td>{product.currency}</td>
            <td className="row-actions"><button disabled={busy} onClick={() => handleEdit(product.sku)}>详情 / 编辑</button><button className="danger-link" disabled={busy} onClick={() => { setDeleting(product); setEditor(null); setError('') }}>删除</button></td></tr>)}</tbody></table></div>}</>}
  </section>
}
