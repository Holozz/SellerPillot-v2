import { request } from './client'
import type { User, LoginResult } from '../types'

export function login(username: string, password: string, signal?: AbortSignal): Promise<LoginResult> {
  return request('/auth/login', {
    method: 'POST', signal,
    body: JSON.stringify({ username, password }),
  })
}

export function getCurrentUser(signal: AbortSignal): Promise<User> {
  return request('/auth/me', { signal })
}
export function register(username: string, password: string, signal: AbortSignal): Promise<User> {
  return request('/auth/register', { method: 'POST', body: JSON.stringify({ username, password }), signal })
}
