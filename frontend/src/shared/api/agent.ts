import { request } from './client'
import type { AgentReply } from '../types'

export function askAgent(
  storeId: number, message: string, signal: AbortSignal, conversationId?: number,
): Promise<AgentReply> {
  return request(`/store/${storeId}/agent/query`, {
    method: 'POST',
    body: JSON.stringify({ message, conversation_id: conversationId }),
    signal,
  })
}
