import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from '../../shared/api/client'
import { getProducts } from '../../shared/api/products'

interface Product { id: number; internal_sku: string; title: string; base_unit: string }
interface Mapping { source_sku: string; internal_sku: string; title: string; mapping_version: number }
export default function CatalogMappings({ storeId, onSessionExpired }: { storeId: number; onSessionExpired: () => void }) {
  const base = `/v2/stores/${storeId}`
  const [products, setProducts] = useState<Product[]>([]), [mappings, setMappings] = useState<Mapping[]>([])
  const [sku, setSku] = useState(''), [title, setTitle] = useState(''), [unit, setUnit] = useState('piece')
  const [sourceSku, setSourceSku] = useState(''), [targetSku, setTargetSku] = useState('')
  const [sourceProducts, setSourceProducts] = useState<{ sku: string; title: string }[]>([])
  const [sourceProductsUnavailable, setSourceProductsUnavailable] = useState(false)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const pending = useRef<AbortController | null>(null)
  async function load(signal: AbortSignal) {
    const [catalog, current, inventory] = await Promise.all([
      request<Product[]>(`${base}/catalog-products`, { signal }),
      request<Mapping[]>(`${base}/product-mappings`, { signal }),
      getProducts(storeId, signal).catch(() => null),
    ])
    if (!signal.aborted) {
      setProducts(catalog); setMappings(current)
      setSourceProducts(inventory ?? []); setSourceProductsUnavailable(inventory === null)
    }
  }
  async function run(work: (signal: AbortSignal) => Promise<void>) {
    if (pending.current) return
    const controller = new AbortController(); pending.current = controller; setBusy(true); setError(''); setNotice('')
    try { await work(controller.signal) } catch (cause) {
      if (controller.signal.aborted) return
      if (cause instanceof ApiError && cause.status === 401) onSessionExpired()
      else setError(cause instanceof Error ? cause.message : '保存失败')
    } finally { if (pending.current === controller) { pending.current = null; if (!controller.signal.aborted) setBusy(false) } }
  }
  useEffect(() => { void run(load); return () => { pending.current?.abort(); pending.current = null } }, [storeId])
  return <section>
    <div className="section-heading"><div><h2>统一商品与店铺 SKU</h2><p>同一卖家的多家店铺可共用统一商品；来源 SKU 的映射按店铺保存。仅所有者可修改。</p></div><button className="quiet-button" disabled={busy} onClick={() => void run(load)}>刷新映射</button></div>
    {error && <p role="alert" className="error-message">{error}</p>}{notice && <p role="status">{notice}</p>}
    <form className="java-query-form" onSubmit={event => { event.preventDefault(); void run(async signal => {
      await request(`${base}/catalog-products`, { method: 'POST', body: JSON.stringify({ internal_sku: sku.trim(), title: title.trim(), base_unit: unit.trim() }), signal })
      await load(signal); if (!signal.aborted) { setTargetSku(sku.trim()); setSku(''); setTitle(''); setNotice('统一商品已创建。') }
    }) }}>
      <label>统一 SKU<input required pattern="[A-Za-z0-9_-]{1,64}" maxLength={64} disabled={busy} value={sku} onChange={event => setSku(event.target.value)} /></label>
      <label>商品名称<input required maxLength={200} disabled={busy} value={title} onChange={event => setTitle(event.target.value)} /></label>
      <label>计量单位<input required maxLength={24} disabled={busy} value={unit} onChange={event => setUnit(event.target.value)} /></label><button className="primary-button compact" disabled={busy}>创建统一商品</button>
    </form>
    <form className="java-query-form" onSubmit={event => { event.preventDefault();
      const prior = mappings.find(mapping => mapping.source_sku === sourceSku.trim())
      if (!window.confirm(`将本店铺 ${sourceSku.trim()} 关联到 ${targetSku}？${prior ? `当前关联为 ${prior.internal_sku}，保存后将追加映射版本。` : ''}`)) return
      void run(async signal => { await request(`${base}/product-mappings/${encodeURIComponent(sourceSku.trim())}`, { method: 'PUT', body: JSON.stringify({ internal_sku: targetSku }), signal }); await load(signal); if (!signal.aborted) setNotice('映射版本已保存，已有业务修订保留原映射。') })
    }}>
      <label>本店铺来源 SKU<input list="source-sku-options" aria-describedby="source-sku-hint" required pattern="[A-Za-z0-9_-]{1,128}" maxLength={128} value={sourceSku} disabled={busy} onChange={event => setSourceSku(event.target.value)} /></label>
      <datalist id="source-sku-options">{sourceProducts.map(product => <option key={product.sku} value={product.sku} label={product.title} />)}</datalist>
      <label>统一商品<select required value={targetSku} disabled={busy} onChange={event => setTargetSku(event.target.value)}><option value="">请选择</option>{products.map(product => <option key={product.id} value={product.internal_sku}>{product.internal_sku} · {product.title}（{product.base_unit}）</option>)}</select></label><button className="primary-button compact" disabled={busy || !targetSku}>确认映射</button>
    </form>
    <p className="hint" id="source-sku-hint">{sourceProductsUnavailable ? '暂时无法加载店铺商品，可直接填写平台文件中的 SKU。' : '可输入 SKU 或商品名称查找店铺商品，也可直接填写平台文件中的 SKU。'}</p>
    <div className="java-table-scroll"><table><thead><tr><th>本店铺来源 SKU</th><th>统一 SKU</th><th>商品</th><th>映射版本</th></tr></thead><tbody>{mappings.map(mapping => <tr key={mapping.source_sku}><td>{mapping.source_sku}</td><td>{mapping.internal_sku}</td><td>{mapping.title}</td><td>{mapping.mapping_version}</td></tr>)}</tbody></table></div>
    {!mappings.length && <p className="hint">先创建统一商品，再关联采购批次和销售明细中使用的来源 SKU。</p>}
  </section>
}
