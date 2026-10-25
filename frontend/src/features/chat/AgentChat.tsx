import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, askAgent } from '../../shared/api'
import type { AgentReply, Store } from '../../shared/api'

interface Props {
  store: Store
  onSessionExpired: () => void
}

interface ChatTurn {
  question: string
  reply: AgentReply
}

export default function AgentChat({ store, onSessionExpired }: Props) {
  const [question, setQuestion] = useState('')
  const [turns, setTurns] = useState<ChatTurn[]>([])
  const [conversationId, setConversationId] = useState<number | undefined>(undefined)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)

  // 切换店铺或退出导致组件卸载时，取消浏览器等待并忽略旧回复。
  useEffect(() => () => pending.current?.abort(), [])

  async function handleSend(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const message = question.trim()
    if (!message || pending.current) return
    const controller = new AbortController()
    pending.current = controller
    setSending(true)
    setError('')
    try {
      const reply = await askAgent(store.id, message, controller.signal, conversationId)
      if (controller.signal.aborted) return
      setTurns((previous) => [...previous, { question: message, reply }])
      setConversationId(reply.conversation_id)
      setQuestion('')
    } catch (cause) {
      if (controller.signal.aborted) return
      if (cause instanceof ApiError && cause.status === 401) {
        onSessionExpired()
      } else {
        setError(cause instanceof Error ? cause.message : '查询失败，请稍后重试。')
      }
    } finally {
      if (pending.current === controller) pending.current = null
      if (!controller.signal.aborted) setSending(false)
    }
  }

  function handleNewConversation() {
    setTurns([])
    setConversationId(undefined)
    setQuestion('')
    setError('')
  }

  return (
    <section className="chat-panel" aria-label="运营问答">
      <div className="chat-heading">
        <div><h2>运营问答</h2><p>当前店铺：{store.name}</p></div>
        <button className="quiet-button" onClick={handleNewConversation}
          disabled={sending || turns.length === 0}>新对话</button>
      </div>

      {turns.length === 0 && <div className="chat-welcome">
        <p>查询库存、订单和销售汇总，或查阅演示政策。</p>
        <div className="question-examples">
          {['哪些商品的库存低于10？', '演示政策允许在签收后多少天申请退货？'].map((sample) => (
            <button key={sample} onClick={() => setQuestion(sample)} disabled={sending}>{sample}</button>
          ))}
        </div>
      </div>}

      <div className="chat-thread" role="log" aria-label="对话记录" aria-live="polite">
        {turns.map((turn, index) => (
          <article className="chat-turn" key={index}>
            <div className="chat-question"><span className="message-label">你的问题</span>
              <p>{turn.question}</p></div>
            <div className="chat-answer"><span className="message-label">SellerPilot</span>
              {turn.reply.policy_status === 'insufficient_evidence' &&
                <span className="evidence-label">资料不足</span>}
              <p className="answer-text">{turn.reply.answer}</p>
              {turn.reply.citations.length > 0 && <div className="citations">
                <h3>演示政策依据</h3>
                {turn.reply.citations.map((citation) => (
                  <details key={citation.chunk_id}>
                    <summary>{citation.title} · {citation.section}</summary>
                    <p>{citation.text}</p>
                    <small>项目自编演示资料，版本 {citation.document_version}</small>
                  </details>
                ))}
              </div>}
            </div>
          </article>
        ))}
      </div>

      {sending && <p className="chat-progress" role="status">正在处理：{question.trim()}</p>}
      {error && <p className="error-message" role="alert">{error}</p>}
      <form className="chat-form" onSubmit={handleSend}>
        <label htmlFor="agent-question">向当前店铺提问</label>
        <textarea id="agent-question" value={question} maxLength={2000} rows={3}
          placeholder="例如：哪些商品的库存低于10？"
          onChange={(event) => setQuestion(event.target.value)} disabled={sending} required />
        <div className="chat-actions"><span>{question.length} / 2000</span>
          <button className="primary-button" type="submit" disabled={sending || !question.trim()}>
            {sending ? '正在查询…' : '发送问题'}
          </button></div>
      </form>
    </section>
  )
}
