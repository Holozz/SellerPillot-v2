import { useEffect, useRef, useState } from 'react'
import { ApiError, confirmTitleDraft, generateTitleDraft, getProducts, getTitleDraft } from '../../shared/api'
import type { Product, Store, TitleDraft } from '../../shared/api'

interface Props {
  store: Store
  onSessionExpired: () => void
}

type Action = 'loading' | 'generating' | 'checking' | 'confirming'

export default function ProductTitles({ store, onSessionExpired }: Props) {
  const [products, setProducts] = useState<Product[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [draft, setDraft] = useState<TitleDraft | null>(null)
  const [reviewed, setReviewed] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [busy, setBusy] = useState<Action | null>('loading')
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  const selectedProduct = products.find((product) => product.id === selectedId)

  // 四种操作共用请求锁、取消、错误和收尾，具体请求内容由operation传入。
  async function runAction(action: Action, operation: (signal: AbortSignal) => Promise<void>) {
    if (pending.current) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(action)
    setError('')
    try {
      // signal 从控制器传进了具体操作
      await operation(controller.signal)
    } 
    // 捕获错误对象
    catch (cause) {
      // 如果错误是离开页面、切换店铺等取消操作引起的，就忽略，不显示成操作失败
      if (controller.signal.aborted) return
      // 如果是接口错误类，就认定登录凭证无效，通知父组件退出登录
      if (cause instanceof ApiError && cause.status === 401) onSessionExpired()
      else {
        if (cause instanceof ApiError && cause.status === 409) setConflict(true)
        setError(cause instanceof Error ? cause.message : '操作失败，请稍后重试。')
      }}
    finally {
      // 清理本次操作的控制器
      if (pending.current === controller) pending.current = null
      // 把页面恢复为空闲状态
      if (!controller.signal.aborted) setBusy(null)
    }
  }

  async function loadProducts(signal: AbortSignal) {
    const items = await getProducts(store.id, signal)
    if (signal.aborted) return
    // 刷新页面商品列表
    setProducts(items)
    // 查看当前选中的商品还在不在新列表里，若不在就把旧勾选状态置空
    if (!items.some((product) => product.id === selectedId)) {
      setSelectedId(items[0]?.id ?? null)
      setDraft(null)
      setReviewed(false)
      setConflict(false)
    }
  }

  useEffect(() => {
    runAction('loading', loadProducts)
    return () => {
      pending.current?.abort()
      pending.current = null
    }
  }, [store.id])

  function handleSelectProduct(productId: number) {
    // 正在处理请求或者点击的就是已经选中的商品
    if (pending.current || productId === selectedId) return
    // 选中新的商品
    setSelectedId(productId)
    // 清除旧的商品的勾选状态
    setDraft(null)
    setReviewed(false)
    setConflict(false)
    setError('')
  }

  function handleGenerate() {
    // 没有选中的商品则直接结束
    if (!selectedProduct) return
    runAction('generating', async (signal) => {
      // 清理旧的预览
      setDraft(null)
      setReviewed(false)
      setConflict(false)
      // 请求新的草稿
      const created = await generateTitleDraft(store.id, selectedProduct.id, signal)
      if (!signal.aborted) setDraft(created)
    })
  }

  function handleConfirm() {
    if (!draft || draft.status !== 'pending' || !reviewed || conflict) return
    runAction('confirming', async (signal) => {
      const applied = await confirmTitleDraft(store.id, draft.draft_id, signal)
      if (signal.aborted) return
      setDraft(applied)
      setReviewed(false)
      // 回执是历史操作记录；重新查询实际商品，避免把历史标题当成当前值。
      await loadProducts(signal)
    })
  }

  function handleCheckDraft() {
    if (!draft) return
    runAction('checking', async (signal) => {
      const current = await getTitleDraft(store.id, draft.draft_id, signal)
      if (signal.aborted) return
      setDraft(current)
      setReviewed(false)
      if (current.status === 'applied') await loadProducts(signal)
    })
  }

  return (
    <section className="titles-panel" aria-label="商品标题">
      <div className="chat-heading">
        <div><h2>商品标题</h2><p>当前店铺：{store.name}。生成建议后，核对内容再确认修改。</p></div>
        <button className="quiet-button" disabled={busy !== null}
          onClick={() => runAction('loading', loadProducts)}>刷新商品</button>
      </div>
      {error && <p className="error-message" role="alert">{error}</p>}
      {busy === 'loading' && <p className="hint" role="status">正在加载商品…</p>}
      {products.length === 0 && busy !== 'loading' && !error &&
        <p className="empty-state">当前店铺还没有商品，添加商品后可在这里刷新。</p>}
      {products.length > 0 && <>
        <div className="title-products" aria-label="选择商品">
          {products.map((product) => (
            <button key={product.id} className={`title-product ${selectedId === product.id ? 'selected' : ''}`}
              aria-pressed={selectedId === product.id} disabled={busy !== null}
              onClick={() => handleSelectProduct(product.id)}>
              <span><strong>{product.title}</strong><small>SKU：{product.sku}</small></span>
              <span className="product-stock">库存 {product.stock}</span>
            </button>
          ))}
        </div>
        {products.length === 100 && <p className="hint">当前显示前100个商品。</p>}
        <button className="primary-button generate-title" disabled={busy !== null || !selectedProduct}
          onClick={handleGenerate}>{busy === 'generating' ? '正在生成…' : '生成标题草稿'}</button>
      </>}

      {draft && <section className="title-review" aria-label="标题草稿预览">
        <div className="draft-heading"><h3>草稿 #{draft.draft_id}</h3>
          <span>{draft.status === 'applied' ? '已应用' : conflict ? '需要重新生成' : '待确认'}</span></div>
        <div className="title-comparison">
          <div><h4>生成时的原标题</h4><p>{draft.original_title}</p></div>
          <div><h4>建议标题</h4><p>{draft.proposed_title}</p></div>
        </div>
        {draft.status === 'applied' ? (
          <p className="title-success" role="status">草稿已应用。
            {draft.applied_at && <span>应用时间（北京时间）：
              <time dateTime={draft.applied_at}>{new Date(draft.applied_at).toLocaleString('zh-CN', {
                timeZone: 'Asia/Shanghai', hour12: false,
              })}</time></span>}
          </p>
        ) : <>
          <p className="hint">建议由模型生成，请核对商品事实、措辞及承诺。确认后会修改商品标题。</p>
          {conflict ? <p className="hint">商品标题已变化，请重新生成并核对草稿。</p> : <label className="review-check">
            <input type="checkbox" checked={reviewed} disabled={busy !== null}
              onChange={(event) => setReviewed(event.target.checked)} />
            我已核对建议标题，确认用于当前商品
          </label>}
          <button className="primary-button apply-title" onClick={handleConfirm}
            disabled={busy !== null || !reviewed || conflict}>
            {busy === 'confirming' ? '正在应用…' : '确认应用标题'}
          </button>
        </>}
        <button className="quiet-button check-draft" onClick={handleCheckDraft}
          disabled={busy !== null}>{busy === 'checking' ? '正在查询…' : '查询草稿状态'}</button>
        <p className="hint">确认响应中断时，可查询草稿状态，了解是否已应用。</p>
      </section>}
    </section>
  )
}
