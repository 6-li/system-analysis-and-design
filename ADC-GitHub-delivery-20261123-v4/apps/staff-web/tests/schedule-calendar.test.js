import test from 'node:test'
import assert from 'node:assert/strict'
import { calendarProjection, calendarWeek } from '../src/services/schedule-calendar.js'
const entry = (id, start, end, status = 'EFFECTIVE') => ({ ref: { scheduleId: id, versionNo: 2 },
  clinicId: 'SYNTHETIC-CLINIC', dentistId: 'SYNTHETIC-DENTIST', startsAt: start, endsAt: end, activationStatus: status })

test('week projection preserves state/version identity and separates overlapping visual lanes', () => {
  const model = calendarProjection([
    entry('1', '2026-11-16T01:00:00Z', '2026-11-16T04:00:00Z'),
    entry('2', '2026-11-16T02:00:00Z', '2026-11-16T03:00:00Z', 'DRAFT'),
    entry('3', '2026-11-16T04:00:00Z', '2026-11-16T05:00:00Z', 'SUPERSEDED'),
  ], 'Asia/Shanghai', '2026-11-16')
  const slots = model.days[0].slots
  assert.deepEqual(slots.map(slot => slot.statusText), ['已生效', '待生效草案', '历史版本'])
  assert.equal(slots[0].refLabel, '1 / 2')
  assert.equal(slots[0].startMinute, 540)
  assert.equal(slots[0].lane, 0)
  assert.equal(slots[1].lane, 1)
  assert.equal(slots[2].lane, 0)
  assert.equal(model.rejected, 0)
})

test('cross-midnight slots use half-open day boundaries without losing microsecond timestamps', () => {
  const model = calendarProjection([
    entry('1', '2026-11-16T23:00:00Z', '2026-11-17T00:00:00Z'),
    entry('2', '2026-11-17T09:00:00.000000Z', '2026-11-17T09:00:00.000001Z'),
  ], 'Etc/UTC', '2026-11-16')
  assert.equal(model.days[0].slots.length, 1)
  assert.equal(model.days[1].slots.length, 1)
  assert.equal(model.days[1].slots[0].endsAt, '2026-11-17T09:00:00.000001Z')
  assert.equal(model.rejected, 0)
})

test('DST projection preserves offsets and missing/invalid zones never use a browser default', () => {
  const model = calendarProjection([entry('1', '2026-11-01T01:45:00-04:00', '2026-11-01T01:15:00-05:00')],
    'America/New_York', '2026-11-01')
  const slot = model.days[6].slots[0]
  assert.equal(slot.offsetChanged, true)
  assert.match(slot.startsLabel, /GMT-4/)
  assert.match(slot.endsLabel, /GMT-5/)
  assert.throws(() => calendarProjection([], '', '2026-11-16'), /ZONE_REQUIRED/)
  assert.throws(() => calendarProjection([], 'ServerDefault', '2026-11-16'))
  assert.deepEqual(calendarWeek('2027-01-01'), ['2026-12-28','2026-12-29','2026-12-30','2026-12-31','2027-01-01','2027-01-02','2027-01-03'])
})
