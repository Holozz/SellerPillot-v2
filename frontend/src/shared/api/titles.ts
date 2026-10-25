import { request } from './client'
import type { TitleDraft } from '../types'

export function generateTitleDraft(
  storeId: number, productId: number, signal: AbortSignal,
): Promise<TitleDraft> {
  return request(`/stores/${storeId}/products/${productId}/title-drafts`, {
    method: 'POST', signal,
  })
}

export function getTitleDraft(
  storeId: number, draftId: number, signal: AbortSignal,
): Promise<TitleDraft> {
  return request(`/stores/${storeId}/title-drafts/${draftId}`, { signal })
}

export function confirmTitleDraft(
  storeId: number, draftId: number, signal: AbortSignal,
): Promise<TitleDraft> {
  return request(`/stores/${storeId}/title-drafts/${draftId}/confirm`, {
    method: 'POST', body: JSON.stringify({ confirmed: true }), signal,
  })
}
