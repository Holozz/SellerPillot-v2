import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { login, register } from '../../shared/api'

export default function AuthForm({ onAuthenticated }: { onAuthenticated: (token: string) => void }) {
  const [registering, setRegistering] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const pending = useRef<AbortController | null>(null)
  useEffect(() => () => pending.current?.abort(), [])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending.current) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true)
    setError('')
    setNotice('')
    try {
      if (registering) {
        await register(username.trim(), password, controller.signal)
        if (controller.signal.aborted) return
        // 注册只创建账号，成功后转到登录，避免自动登录失败后重复注册。
        setRegistering(false)
        setPassword('')
        setNotice('账号已创建，请输入密码登录。')
      } else {
        const reply = await login(username.trim(), password, controller.signal)
        if (!controller.signal.aborted) {
          setPassword('')
          onAuthenticated(reply.access_token)
        }
      }
    } catch (cause) {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '操作失败，请重试。')
    } finally {
      if (pending.current === controller) {
        pending.current = null
        if (!controller.signal.aborted) setBusy(false)
      }
    }
  }

  return <div className="auth-layout">
    <div className="auth-intro"><span className="auth-symbol" aria-hidden="true">S</span>
      <h1>掌握每一笔经营。</h1><p>管理商品与库存，核对经营数据，审核每一次 AI 建议。</p>
      <div className="auth-capabilities"><span>商品管理</span><span>经营分析</span><span>运营助手</span></div>
    </div>
    <form className="login-form" onSubmit={handleSubmit}>
      <h2>{registering ? '创建账号' : '登录 SellerPilot'}</h2>
      <p>{registering ? '创建自己的店铺工作台。' : '登录后继续你的运营工作。'}</p>
      <label htmlFor="username">用户名</label>
      <input id="username" autoComplete="username" required minLength={3} maxLength={50}
        pattern={registering ? '[A-Za-z0-9_-]+' : undefined} value={username}
        onChange={event => setUsername(event.target.value)} disabled={busy} />
      <label htmlFor="password">密码</label>
      <input id="password" type="password" required minLength={registering ? 8 : undefined} maxLength={128}
        autoComplete={registering ? 'new-password' : 'current-password'} value={password}
        onChange={event => setPassword(event.target.value)} disabled={busy} />
      {notice && <p className="success-message" role="status">{notice}</p>}
      {error && <p className="error-message" role="alert">{error}</p>}
      <button className="primary-button" disabled={busy}>{busy ? '正在处理…' : registering ? '注册账号' : '登录工作台'}</button>
      <button className="text-button" type="button" disabled={busy} onClick={() => {
        setRegistering(value => !value); setPassword(''); setError(''); setNotice('')
      }}>{registering ? '已有账号，返回登录' : '还没有账号？注册'}</button>
    </form>
  </div>
}
