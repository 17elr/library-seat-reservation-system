import { reactive } from 'vue'
export const session = reactive({ user: null, expired: false })
let token = ''
export class ApiError extends Error {
  constructor(status, data) {
    super(data.message || '请求未完成')
    this.status = status
    this.errors = data.errors || {}
  }
}
export async function csrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('暂时无法建立会话，请重试')
  token = (await response.json()).token
  return token
}
export async function api(path, { method = 'GET', body, signal } = {}) {
  const headers = { Accept: 'application/json' }
  if (method !== 'GET') {
    if (!token) await csrf()
    headers['X-CSRF-TOKEN'] = token
    headers['Content-Type'] = 'application/json'
  }
  let response
  try {
    response = await fetch('/api' + path, {
      method,
      credentials: 'same-origin',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal
    })
  } catch (e) {
    if (e.name === 'AbortError') throw e
    throw new Error('网络连接中断，请重试；已填内容会保留。')
  }
  const text = await response.text()
  let data
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    throw new Error('服务器响应格式异常，请稍后重试')
  }
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/auth/login')) {
      session.expired = !!session.user || session.expired
      session.user = null
      token = ''
    }
    throw new ApiError(response.status, data || {})
  }
  if (path === '/auth/login') token = data.token
  if (path === '/me/password') {
    clearPending()
    session.user = null
    session.expired = true
    token = ''
  }
  return data
}
export function clearPending() {
  if (session.user) sessionStorage.removeItem('library:pending:' + session.user.id)
}
export async function logout() {
  await api('/auth/logout', { method: 'POST' })
  clearPending()
  session.user = null
  token = ''
}
