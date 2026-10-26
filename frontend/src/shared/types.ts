export interface User {
  id: number
  username: string
}

export interface Store {
  id: number
  name: string
  platform: string
  owner_id: number | null
}

export interface LoginResult {
  access_token: string
  token_type: string
}

export interface CsvIssue {
  row: number | null
  field: string
  reason: string
}

export interface Product {
  id: number
  sku: string
  title: string
  price: string
  currency: string
  stock: number
  store_id: number | null
}

export interface ImportResult {
  import_job_id: number
  row_count: number
  data_version: number
  duplicate: boolean
}

export type ImportKind = 'orders' | 'order-items'
