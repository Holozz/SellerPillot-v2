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

export interface PolicyCitation {
  chunk_id: string
  title: string
  section: string
  text: string
  document_version: string
}

export interface AgentReply {
  answer: string
  citations: PolicyCitation[]
  policy_status?: 'answered' | 'insufficient_evidence'
  conversation_id: number
  conversation_version: number
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

export interface TitleDraft {
  draft_id: number
  store_id: number
  product_id: number
  original_title: string
  proposed_title: string
  status: 'pending' | 'applied'
  created_at: string
  created_by?: number
  applied_by?: number | null
  applied_at?: string | null
}

export interface ImportResult {
  import_job_id: number
  row_count: number
  data_version: number
  duplicate: boolean
}

export interface SalesGroup {
  currency: string
  order_count: number
  units_sold: number
  // 后端用字符串传递精确金额，展示时不转成浮点数，也不跨币种相加。
  gross_sales: string
  refund_amount: string
  net_sales: string
  orders_without_items: number
}

export interface QueryContext {
  store_id: number
  start_at: string
  end_at: string
  data_version: number
}

export interface SalesSummary extends QueryContext {
  included_statuses: OrderStatus[]
  has_orders_without_items: boolean
  by_currency: SalesGroup[]
}

export interface OrderPage extends QueryContext {
  status_filter: OrderStatus | null
  limit: number
  offset: number
  has_more: boolean
  items: {
    id: number
    external_order_id: string
    ordered_at: string
    status: OrderStatus
    currency: string
  }[]
}

export interface SalesRange {
  start_at: string
  end_at: string
}

export type ImportKind = 'orders' | 'order-items'
export type OrderStatus = 'pending' | 'paid' | 'shipped' | 'cancelled'

export interface ProductSalesPage extends QueryContext {
  included_statuses: OrderStatus[]
  limit: number
  offset: number
  has_more: boolean
  items: {
    product_id: number
    sku: string
    title: string
    currency: string
    units_sold: number
    order_count: number
    gross_sales: string
    refund_amount: string
    net_sales: string
  }[]
}
