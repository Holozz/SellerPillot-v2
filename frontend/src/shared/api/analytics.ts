import { request } from './client'
import type { ProductSalesPage } from '../types'
import type { User, Store, LoginResult, CsvIssue, PolicyCitation, AgentReply, Product, TitleDraft, ImportResult, SalesGroup, QueryContext, SalesSummary, OrderPage, SalesRange, ImportKind, OrderStatus } from '../types'

export function getSalesSummary(
  storeId: number, range: SalesRange, signal: AbortSignal,
): Promise<SalesSummary> {
  // URLSearchParams负责转义时间中的冒号、加号等字符，避免手工拼接破坏时区。
  const query = new URLSearchParams({ ...range })
  return request(`/stores/${storeId}/sales/summary?${query}`, { signal })
}

export function getOrderPage(
  storeId: number, range: SalesRange, status: OrderStatus | '',
  offset: number, signal: AbortSignal,
): Promise<OrderPage> {
  const query = new URLSearchParams({ ...range, limit: '20', offset: String(offset) })
  // “全部状态”表示不传status，而不是向后端发送空字符串。
  if (status) query.set('status', status)
  return request(`/stores/${storeId}/orders?${query}`, { signal })
}
export function getProductSales(storeId: number, range: SalesRange, offset: number, signal: AbortSignal): Promise<ProductSalesPage> {
  const query = new URLSearchParams({ ...range, limit: '20', offset: String(offset) })
  return request(`/stores/${storeId}/sales/products?${query}`, { signal })
}
