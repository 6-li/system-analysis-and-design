import test from 'node:test'
import assert from 'node:assert/strict'
import { scheduleDays } from '../src/services/schedule-display.js'

test('groups and orders effective schedule projections using the explicit clinic time zone', () => {
  const days = scheduleDays([
    { scheduleId: '2', versionNo: 3, startsAt: '2026-12-01T15:30:00Z', endsAt: '2026-12-01T17:00:00Z', appointmentCount: 1 },
    { scheduleId: '1', versionNo: 2, startsAt: '2026-12-01T01:00:00Z', endsAt: '2026-12-01T03:00:00Z', appointmentCount: 2 },
  ], 'Asia/Shanghai')
  assert.equal(days[0].date, '2026-12-01')
  assert.deepEqual(days[0].entries.map(item => item.scheduleId), ['1', '2'])
  assert.equal(days[0].entries[0].startLabel, '09:00')
  assert.equal(days[0].entries[1].endLabel, '2026-12-02 01:00')
  assert.equal(days[0].entries[0].versionNo, 2)
  assert.throws(() => scheduleDays([], ''), /CLINIC_TIME_ZONE_REQUIRED/)
})
