import { request } from './client'
import type { ImportResult, ImportKind } from '../types'

export function importCsv(
  storeId: number, kind: ImportKind, file: File, signal: AbortSignal,
): Promise<ImportResult> {
  // File是浏览器读取的文件对象，用FormData传递文件字节，而不是JSON.stringify
  const body = new FormData()
  // 字段名必须与后端UploadFile参数file一致
  body.append('file', file)
  // request复用JWT和错误处理；文件请求的Content-Type留给浏览器生成
  return request(`/stores/${storeId}/imports/${kind}`, {
    method: 'POST', body, signal,
  })
}
