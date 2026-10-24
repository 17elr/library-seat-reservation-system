let epoch = Date.now(),
  received = Date.now()
export function syncTime(value) {
  const parsed = Date.parse(value)
  if (!Number.isFinite(parsed)) throw new Error('服务器时间格式异常，请重试')
  epoch = parsed
  received = Date.now()
}
export function serverNow() {
  return epoch + Date.now() - received
}
export function localTime(ms = serverNow()) {
  return new Date(ms + 8 * 3600000).toISOString().slice(0, 19)
}
export function today() {
  return localTime().slice(0, 10)
}
export function dateAdd(d, n) {
  return new Date(Date.parse(d + 'T00:00:00+08:00') + n * 86400000 + 8 * 3600000)
    .toISOString()
    .slice(0, 10)
}
export function epochOf(value) {
  return Date.parse(
    value.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(value) ? value : value + '+08:00'
  )
}
export function fmt(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}
export function minutes(t) {
  return Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5))
}
export function hhmm(n) {
  return String(Math.floor(n / 60)).padStart(2, '0') + ':' + String(n % 60).padStart(2, '0')
}
export const statusNames = {
  RESERVED: '待签到',
  CHECKED_IN: '已签到',
  TEMP_LEFT: '暂离中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  NO_SHOW: '未签到超时',
  LEAVE_TIMEOUT: '暂离超时',
  ADMIN_CLOSED: '管理关闭'
}
export function roomPhoto(r) {
  return (
    '/images/' +
    ({ R1: 'library-study', R2: 'library-books', R3: 'library-reading' }[r.code] ||
      'library-study') +
    '.webp'
  )
}
export function errorText(e) {
  return e.status === 403 ? '无操作权限，或页面凭据已过期，请刷新后重试。' : e.message
}
