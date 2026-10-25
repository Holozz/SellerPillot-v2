import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, createStore, getStore } from '../../shared/api'
import type { Store } from '../../shared/types'

interface Props {
  stores: Store[]
  selectedId: number | null
  onSelect: (id: number) => void
  onCreated: (store: Store) => void
  onSessionExpired: () => void
}

export default function StoreManager({ stores, selectedId, onSelect, onCreated, onSessionExpired }: Props) {
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [platform, setPlatform] = useState('amazon')
  const [details, setDetails] = useState<Store | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  useEffect(() => () => pending.current?.abort(), [])
  useEffect(() => { pending.current?.abort(); pending.current = null; setBusy(false); setDetails(null); setError('') }, [selectedId])

  async function runStoreAction(operation: (signal: AbortSignal) => Promise<void>) {
    if (pending.current) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError('')
    try { await operation(controller.signal) }
    catch (cause) {
      if (controller.signal.aborted) return
      if (cause instanceof ApiError && cause.status === 401) onSessionExpired()
      else setError(cause instanceof Error ? cause.message : '店铺操作失败。')
    } finally {
      if (pending.current === controller) { pending.current = null; setBusy(false) }
    }
  }

  function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void runStoreAction(async signal => {
      const store = await createStore(name.trim(), platform, signal)
      if (signal.aborted) return
      // 将服务器返回的新店铺交给App选中，不在前端猜测ID或所有者。
      setCreating(false); setName(''); onCreated(store)
    })
  }

  return <section className="store-manager" aria-label="店铺管理">
    <div className="store-toolbar">
      <label>当前店铺<select aria-label="当前店铺" value={selectedId ?? ''} disabled={busy}
        onChange={event => onSelect(Number(event.target.value))}>
        <option value="" disabled>选择店铺</option>
        {stores.map(store => <option key={store.id} value={store.id}>{store.name} · {store.platform}</option>)}
      </select></label>
      <button className="quiet-button" onClick={() => { setCreating(value => !value); setError('') }} disabled={busy}>创建店铺</button>
      <button className="quiet-button" disabled={busy || selectedId === null} onClick={() => {
        if (selectedId === null) return
        void runStoreAction(async signal => { const store = await getStore(selectedId, signal); if (!signal.aborted) setDetails(store) })
      }}>店铺详情</button>
      {stores.length === 100 && <span className="hint">当前最多显示100家店铺</span>}
    </div>
    {error && <p className="error-message" role="alert">{error}</p>}
    {details && <div className="store-detail" role="status"><strong>{details.name}</strong><span>店铺 #{details.id}</span><span>{details.platform}</span></div>}
    {creating && <form className="editor-form store-create" onSubmit={handleCreate}>
      <label>店铺名称<input required maxLength={100} value={name} disabled={busy} onChange={event => setName(event.target.value)} /></label>
      <label>平台<select value={platform} disabled={busy} onChange={event => setPlatform(event.target.value)}><option value="amazon">Amazon</option><option value="shopify">Shopify</option></select></label>
      <button className="primary-button" disabled={busy || !name.trim()}>{busy ? '正在保存…' : '保存店铺'}</button>
      <button className="quiet-button" type="button" disabled={busy} onClick={() => setCreating(false)}>取消</button>
    </form>}
    {stores.length === 0 && !creating && <p className="hint">还没有店铺，创建第一家店铺后即可管理商品。</p>}
  </section>
}
