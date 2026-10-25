import type { CsvIssue } from '../types'
export const TOKEN_KEY = 'sellerpilot_token'

export class ApiError extends Error {
  // 普通接口没有行级错误时使用空数组；CSV接口保留明细供页面逐条展示。
  constructor(public status: number, message: string, public issues: CsvIssue[] = []) {
    super(message)
  }
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  const token = sessionStorage.getItem(TOKEN_KEY)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  // JSON请求仍设置JSON类型；FormData由浏览器生成multipart类型和boundary。
  // boundary是文件各部分的分隔标识，手动设置JSON头或仅写multipart都会破坏解析。
  if (options.body && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(`/api${path}`, { ...options, headers })
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error
    throw new Error('无法连接服务，请检查网络后重试。')
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => null)
    // FastAPI普通错误的detail是字符串；CSV错误是包含message/errors的对象。
    const detail = payload?.detail
    const message = typeof detail === 'string' ? detail
      : typeof detail?.message === 'string' ? detail.message
      : Array.isArray(detail) ? detail.map((issue: { msg?: string }) => issue.msg ?? '参数无效').join('；')
      : `请求失败（${response.status}），请稍后重试。`
    // 保留后端提供的行号、字段和原因，而不是把整个对象压成一条笼统提示。
    const issues: CsvIssue[] = Array.isArray(detail?.errors) ? detail.errors : []
    throw new ApiError(response.status, message, issues)
  }
  // DELETE成功的204没有JSON响应体，不能继续调用response.json()。
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
