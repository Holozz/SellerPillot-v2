import { request } from './client'
import type { Store } from '../types'

export function getStores(signal: AbortSignal): Promise<Store[]> {
  return request('/stores?limit=100', { signal })
}
export function createStore(name: string, platform: string, signal: AbortSignal): Promise<Store> {
  return request('/stores', { method: 'POST', body: JSON.stringify({ name, platform }), signal })
}

export function getStore(storeId: number, signal: AbortSignal): Promise<Store> {
  return request(`/stores/${storeId}`, { signal })
}
