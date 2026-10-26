import { useEffect, useState } from 'react'
import { ApiError, getCurrentUser, getStores, TOKEN_KEY } from '../shared/api'
import type { Store, User } from '../shared/types'
import AuthForm from '../features/auth/AuthForm'
import StoreManager from '../features/stores/StoreManager'
import StoreWorkspace from './StoreWorkspace'
import JavaWorkspace from '../features/java/JavaWorkspace'

export default function App() {
  const [token, setToken] = useState(() => sessionStorage.getItem(TOKEN_KEY) ?? '')
  const [user, setUser] = useState<User | null>(null)
  const [stores, setStores] = useState<Store[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  const selectedStore = stores.find(store => store.id === selectedId)

  function handleLogout(message = '') {
    sessionStorage.removeItem(TOKEN_KEY)
    setToken(''); setUser(null); setStores([]); setSelectedId(null); setError(message)
  }

  function handleSessionExpired() { handleLogout('登录已过期，请重新登录。') }

  useEffect(() => {
    if (!token) return
    const controller = new AbortController()
    setLoading(true); setError('')
    Promise.all([getCurrentUser(controller.signal), getStores(controller.signal)])
      .then(([currentUser, ownStores]) => {
        if (controller.signal.aborted) return
        setUser(currentUser); setStores(ownStores)
        setSelectedId(current => ownStores.some(store => store.id === current) ? current : ownStores[0]?.id ?? null)
      }).catch((cause: unknown) => {
        if (controller.signal.aborted) return
        if (cause instanceof ApiError && cause.status === 401) handleSessionExpired()
        else setError(cause instanceof Error ? cause.message : '加载失败，请重试。')
      }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [token, refresh])

  function handleStoreCreated(store: Store) {
    setStores(current => [...current, store])
    setSelectedId(store.id)
  }

  return <div className="app-shell">
    <header className="topbar"><a className="brand" href="/" aria-label="SellerPilot 首页">
      <span className="brand-mark" aria-hidden="true">S</span><span>SellerPilot<small>商家运营控制台</small></span>
    </a>{token && <div className="account"><span>{user?.username ?? '验证账号中'}</span><button className="quiet-button" onClick={() => handleLogout()}>退出登录</button></div>}</header>
    <main>{!token ? <>
      {error && <p className="error-message" role="alert">{error}</p>}
      <AuthForm onAuthenticated={value => { sessionStorage.setItem(TOKEN_KEY, value); setToken(value); setError('') }} />
    </> : <section className="workspace">
      <div className="section-heading"><div><h1>运营工作台</h1><p>商品、数据与运营决策，在一个工作区完成。</p></div>
        <button className="quiet-button" disabled={loading} onClick={() => setRefresh(value => value + 1)}>刷新店铺</button></div>
      {error && <p className="error-message" role="alert">{error}</p>}
      {loading && <p className="hint" role="status">正在加载账号与店铺…</p>}
      {user && <StoreManager stores={stores} selectedId={selectedId} onSelect={setSelectedId}
        onCreated={handleStoreCreated} onSessionExpired={handleSessionExpired} />}
      {selectedStore && user && (import.meta.env.VITE_BACKEND === 'java'
        ? <JavaWorkspace key={`${user.id}:${selectedStore.id}`} store={selectedStore} onSessionExpired={handleSessionExpired} />
        : <StoreWorkspace key={`${user.id}:${selectedStore.id}`} store={selectedStore} onSessionExpired={handleSessionExpired} />)}
    </section>}</main>
    <footer>SellerPilot<span>数据先核对，修改需确认。</span></footer>
  </div>
}
