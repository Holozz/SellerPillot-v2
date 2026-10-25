import { request } from './client'
import type { Product } from '../types'

export function getProducts(storeId: number, signal: AbortSignal): Promise<Product[]> {
  return request(`/products?store_id=${storeId}&limit=100`, { signal })
}

export function getLowStockProducts(
  storeId: number, threshold: number, signal: AbortSignal,
): Promise<Product[]> {
  // 让后端先按店铺和阈值筛选，再限制条数，不能在前端已取到的100个商品内筛选。
  const query = new URLSearchParams({ threshold: String(threshold), limit: '100' })
  return request(`/stores/${storeId}/products/low-stock?${query}`, { signal })
}
export interface ProductInput {
  sku: string
  title: string
  price: string
  currency: string
  stock: number
  store_id: number
}

export function getProduct(sku: string, signal: AbortSignal, storeId?: number): Promise<Product> {
  return request(`/products/${encodeURIComponent(sku)}${storeId ? `?store_id=${storeId}` : ''}`, { signal })
}

export function createProduct(product: ProductInput, signal: AbortSignal): Promise<Product> {
  return request('/products', { method: 'POST', body: JSON.stringify(product), signal })
}

export function updateProduct(sku: string, product: Omit<ProductInput, 'sku' | 'store_id'>, signal: AbortSignal, storeId?: number): Promise<Product> {
  return request(`/products/${encodeURIComponent(sku)}${storeId ? `?store_id=${storeId}` : ''}`, { method: 'PUT', body: JSON.stringify(product), signal })
}

export function deleteProduct(sku: string, signal: AbortSignal, storeId?: number): Promise<void> {
  return request(`/products/${encodeURIComponent(sku)}${storeId ? `?store_id=${storeId}` : ''}`, { method: 'DELETE', signal })
}
