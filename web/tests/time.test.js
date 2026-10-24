import { test } from 'node:test'
import assert from 'node:assert/strict'
import { syncTime, today, dateAdd, epochOf, minutes, hhmm } from '../src/time.js'
test('Shanghai dates and grid are independent of browser timezone', () => {
  syncTime('2026-10-09T00:01:00+08:00')
  assert.equal(today(), '2026-10-09')
  assert.throws(() => syncTime(undefined), /服务器时间格式异常/)
  assert.equal(today(), '2026-10-09')
  assert.equal(dateAdd('2026-12-31', 1), '2027-01-01')
  assert.equal(epochOf('2026-10-09T10:45:00'), Date.parse('2026-10-09T02:45:00Z'))
  assert.equal(hhmm(minutes('10:30') + 90), '12:00')
})
