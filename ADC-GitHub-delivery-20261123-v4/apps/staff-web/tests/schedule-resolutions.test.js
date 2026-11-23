import test from 'node:test'
import assert from 'node:assert/strict'
import { resolutionDisposition, scheduleApi } from '../src/api/schedules.js'

test('only confirmed processing saves expose a new record; contradicted and lost receipts stay unknown', () => {
  const data = { ucCode: 'UC22', operationKind: 'RESOLUTION_SAVE', status: 'COMPLETED',
    businessOutcome: 'SUCCEEDED', auditPersistence: 'CONFIRMED', requestReference: 'synthetic',
    links: { resolutionId: '42' } }
  assert.equal(resolutionDisposition({ http: 200, data }), 'saved')
  assert.equal(resolutionDisposition({ http: 200, data: { ...data, businessOutcome: 'REJECTED' } }), 'unknown')
  assert.equal(resolutionDisposition({ http: 202, data }), 'unknown')
  assert.equal(resolutionDisposition({ http: 0, data: {} }), 'unknown')
})

test('stale head and stale appointment require a new review; forbidden is rejected', () => {
  for (const code of ['RESOLUTION_HEAD_STALE', 'RESOLUTION_APPOINTMENT_STALE']) {
    assert.equal(resolutionDisposition({ http: 409, data: { code } }), 'stale')
  }
  assert.equal(resolutionDisposition({ http: 403, data: { code: 'FORBIDDEN' } }), 'rejected')
  assert.equal(resolutionDisposition({ http: 409, data: { code: 'REQUEST_CONFLICT', requestReference: 'ref' } }), 'rejected')
  assert.equal(resolutionDisposition({ http: 503, data: { code: 'OPERATION_FAILED', requestReference: 'ref' } }), 'rejected')
  assert.equal(resolutionDisposition({ http: 503, data: { code: 'OPERATION_FAILED' } }), 'unknown')
})

test('history API keeps multiple roots and save carries the original key and CSRF', async () => {
  const calls = []
  const api = scheduleApi(async (url, options) => {
    calls.push({ url, options })
    return { status: 200, json: async () => [{ rootResolutionId: '1' }, { rootResolutionId: '2' }] }
  })
  assert.equal((await api.histories('7', 2)).data.length, 2)
  await api.saveResolution('7', 2, { appointmentId: '10' }, 'original', 'csrf')
  assert.match(calls[0].url, /7\/versions\/2\/resolution-histories$/)
  assert.match(calls[1].url, /7\/versions\/2\/resolutions$/)
  assert.equal(calls[1].options.headers['Idempotency-Key'], 'original')
  assert.equal(calls[1].options.headers['X-CSRF-Token'], 'csrf')
})
