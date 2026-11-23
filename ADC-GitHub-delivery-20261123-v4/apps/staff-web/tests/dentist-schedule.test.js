import test from 'node:test'
import assert from 'node:assert/strict'
import { readDentistSchedule } from '../src/api/dentist-schedule.js'

test('reads a personal effective schedule with session credentials and an opaque paging position', async () => {
  const result = await readDentistSchedule('87000', async (url, options) => {
    assert.equal(url, '/api/v1/staff/my-schedule?cursor=87000')
    assert.equal(options.credentials, 'same-origin')
    assert.equal(options.cache, 'no-store')
    return Response.json({ items: [{ scheduleId: '87001', clinicId: '81001', versionNo: 2, appointmentCount: 2,
      startsAt: '2026-12-01T09:00:00+08:00', endsAt: '2026-12-01T12:00:00+08:00' }] })
  })
  assert.equal(result.items[0].appointmentCount, 2)
})
