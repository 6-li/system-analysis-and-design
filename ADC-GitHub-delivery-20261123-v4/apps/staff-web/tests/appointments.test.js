import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { appointmentApi, appointmentDisposition, durationBasis } from '../src/api/appointments.js'

const { paths, components: { schemas } } = JSON.parse(readFileSync(
  new URL('../../../specs/002-dentist-scheduling/contracts/api.openapi.json', import.meta.url), 'utf8'))
const base = '/staff/appointments'
const operations = {
  12: paths[base + '/{appointmentId}/schedule-support'].get,
  13: paths[base].post,
  14: paths[base + '/{appointmentId}/changes'].post,
  15: paths[base + '/{appointmentId}/cancellations'].post,
  16: paths[base + '/{appointmentId}/arrival-verifications'].post,
  17: paths[base + '/{appointmentId}/no-show-verifications'].post,
  18: paths[base + '/{appointmentId}'].get,
}
const receiptSchema = schemas.CommandReceipt
const scenario = status => receiptSchema.oneOf.find(item => item.properties.status.enum.includes(status))
const responseSchema = (operation, status) => operations[operation].responses[status].content['application/json'].schema
const reference = 'SYNTHETIC/request/original'
const original = Object.freeze({ primaryProjectId: '501', projectVersion: 2, adoptedDurationMinutes: 27 })
const latestSameProject = Object.freeze({ ...original, projectVersion: 3, adoptedDurationMinutes: 35 })
const currentNewProject = Object.freeze({ primaryProjectId: '502', projectVersion: 5, adoptedDurationMinutes: 40 })

function changeFixture(basis, basisMode) {
  return {
    expectedAppointmentToken: 'SYNTHETIC/appointment/old-token',
    scheme: {
      effectiveScheduleRef: { scheduleId: '801', versionNo: 2 },
      startsAt: '2026-11-12T09:00:00.123456+08:00',
      endsAt: basis.adoptedDurationMinutes === 27 ? '2026-11-12T09:27:00.123456+08:00' : '2026-11-12T09:40:00.123456+08:00',
      treatmentRequest: 'SYNTHETIC request',
      durationBasis: { ...basis, schemaVersion: 1, basisMode },
    },
    acceptanceEvidenceReference: 'SYNTHETIC/accepted-exact-proposal',
    changeBasis: 'SYNTHETIC human reason',
  }
}

function reply(status, operationKind, ucCode, extras = {}) {
  return {
    requestReference: reference, ucCode, operationKind, status,
    businessOutcome: status === 'UNKNOWN' ? 'UNKNOWN' : 'SUCCEEDED',
    message: 'SYNTHETIC reply', auditPersistence: status === 'UNKNOWN' ? 'UNKNOWN' : 'CONFIRMED',
    existingResult: false, ...extras,
  }
}

test('T032 matches the staff operations and preserves separate booking and clinic reception duties', () => {
  for (const number of [12, 13, 14, 15, 16, 17, 18]) {
    assert.equal(operations[number].operationId, 'SCH-OP-' + String(number).padStart(3, '0'))
  }
  for (const number of [13, 14, 15]) assert.match(operations[number].description, /预约接待人员/)
  for (const number of [16, 17]) assert.match(operations[number].description, /诊所接待人员/)
})

test('T032 time or schedule change carries original minutes and version even when catalog has changed', () => {
  const change = changeFixture(original, 'PRESERVED_ORIGINAL')
  assert.deepEqual(change.scheme.durationBasis, { ...original, schemaVersion: 1, basisMode: 'PRESERVED_ORIGINAL' })
  assert.notEqual(change.scheme.durationBasis.projectVersion, latestSameProject.projectVersion)
  assert.notEqual(change.scheme.durationBasis.adoptedDurationMinutes, latestSameProject.adoptedDurationMinutes)
  assert.match(change.scheme.endsAt, /09:27:00\.123456\+08:00$/)
})

test('T032 project change presents the new current version with evidence for that exact proposal', () => {
  const change = changeFixture(currentNewProject, 'CHANGED_PROJECT_CURRENT')
  assert.deepEqual(change.scheme.durationBasis, { ...currentNewProject, schemaVersion: 1, basisMode: 'CHANGED_PROJECT_CURRENT' })
  assert.match(change.scheme.endsAt, /09:40:00\.123456\+08:00$/)
  assert.ok(schemas.ChangeAppointmentInput.required.every(key => Object.hasOwn(change, key)))
  assert.ok(schemas.ChangeAppointmentInput.required.includes('acceptanceEvidenceReference'))
  assert.ok(schemas.BookingScheme.properties.durationBasis.properties.basisMode.enum.includes(change.scheme.durationBasis.basisMode))
  assert.equal(schemas.CreateAppointmentInput.properties.scheme.properties.durationBasis.properties.basisMode.const, 'INITIAL_CURRENT')
})

test('T032 stale replies use the approved conflict shape and do not supply a confirmed appointment', async () => {
  for (const code of ['STALE_DURATION_BASIS', 'STALE_SCHEDULE', 'STALE_APPOINTMENT']) {
    const response = Response.json({ code, message: 'SYNTHETIC conflict', requestReference: reference }, { status: 409 })
    assert.equal(responseSchema(14, response.status).$ref, '#/components/schemas/Error')
    const body = await response.json()
    assert.ok(schemas.Error.required.every(key => Object.hasOwn(body, key)))
    assert.equal(body.requestReference, reference)
    assert.equal(Object.hasOwn(body, 'links'), false)
  }
})

test('T032 unknown mutation outcomes have no success links across all appointment write operations', async () => {
  for (const [number, operation, uc] of [
    [13, 'APPOINTMENT_CREATE', 'UC02'], [14, 'APPOINTMENT_CHANGE', 'UC03'],
    [15, 'APPOINTMENT_CANCEL', 'UC04'], [16, 'ARRIVAL_VERIFY', 'UC05'], [17, 'NO_SHOW_VERIFY', 'UC06'],
  ]) {
    const mock = Response.json(reply('UNKNOWN', operation, uc), { status: 202 })
    const schema = responseSchema(number, mock.status)
    assert.equal(schema.allOf[0].$ref, '#/components/schemas/CommandReceipt')
    assert.ok(schema.allOf[1].properties.status.enum.includes('UNKNOWN'))
    const body = await mock.json()
    assert.ok(receiptSchema.required.every(key => Object.hasOwn(body, key)))
    assert.equal(body.requestReference, reference)
    assert.equal(body.auditPersistence, 'UNKNOWN')
    assert.ok(scenario('UNKNOWN').not.required.includes('links'))
    assert.equal(Object.hasOwn(body, 'links'), false)
  }
})

test('T032 change success needs an actual history ID and arrival success needs an actual visit ID', () => {
  const success = scenario('COMPLETED').allOf[0].oneOf
  for (const [uc, requiredLink] of [['UC03', 'appointmentChangeId'], ['UC05', 'visitId']]) {
    const schema = success.find(item => item.properties.ucCode.enum.includes(uc))
    assert.ok(schema.properties.links.required.includes(requiredLink))
    assert.ok(schema.properties.links.required.includes('originalUcSuccessEventId'))
  }
})

test('T032 persisted incomplete attendance supplies no visit and is distinct from a confirmed outcome', () => {
  const schema = scenario('PERSISTED_INCOMPLETE').oneOf.find(item => item.properties.ucCode.enum.includes('UC05'))
  assert.deepEqual(schema.properties.ucCode.enum, ['UC05', 'UC06'])
  assert.ok(schema.properties.links.required.includes('appointmentId'))
  assert.ok(schema.properties.links.not.required.includes('visitId'))
  const body = reply('PERSISTED_INCOMPLETE', 'ARRIVAL_VERIFY', 'UC05', {
    businessOutcome: 'PENDING_VERIFICATION', links: { appointmentId: '301', originalUcSuccessEventId: '999' },
  })
  assert.equal(Object.hasOwn(body.links, 'visitId'), false)
  assert.notEqual(body.businessOutcome, 'SUCCEEDED')
})

// Page interactions are covered by browser-tests/appointments.spec.js.
test('T033 duration selection uses the original basis for the same project and rejects missing facts', () => {
  assert.deepEqual(durationBasis('change', original, latestSameProject),
    { ...original, schemaVersion: 1, basisMode: 'PRESERVED_ORIGINAL' })
  assert.deepEqual(durationBasis('change', original, currentNewProject),
    { ...currentNewProject, schemaVersion: 1, basisMode: 'CHANGED_PROJECT_CURRENT' })
  assert.equal(durationBasis('change', null, currentNewProject), null)
  assert.equal(durationBasis('create', null, { ...currentNewProject, adoptedDurationMinutes: 0 }), null)
})

test('T033 receipt handling recognizes stale and unknown, and rejects mismatched confirmation', () => {
  const done = reply('COMPLETED', 'APPOINTMENT_CHANGE', 'UC03', {
    links: { appointmentId: '301', appointmentChangeId: '901', originalUcSuccessEventId: '99' },
  })
  const classify = (http, data) => appointmentDisposition('change', { http, data }, reference, '301')
  assert.equal(classify(200, done), 'saved')
  assert.equal(classify(200, { ...done, requestReference: 'another request' }), 'unknown')
  assert.equal(classify(200, { ...done, links: { ...done.links, appointmentId: '302' } }), 'unknown')
  assert.equal(classify(202, { status: 'UNKNOWN' }), 'unknown')
  assert.equal(classify(503, { code: 'CHANGE_UNAVAILABLE', message: 'SYNTHETIC error' }), 'unknown')
  assert.equal(classify(409, { code: 'STALE_APPOINTMENT', message: 'SYNTHETIC stale' }), 'stale')
})

test('T033 transport submits only approved staff endpoints and propagates the original headers', async () => {
  const calls = []
  const api = appointmentApi(async (path, options) => {
    calls.push([path, options])
    return Response.json({})
  })
  await api.read('901')
  await api.read('901', true)
  for (const action of ['create', 'change', 'cancel', 'arrival', 'noShow'])
    await api.write(action, '901', { synthetic: true }, 'original-key', 'csrf')
  assert.deepEqual(calls.map(([path]) => path), [
    '/api/v1/staff/appointments/901', '/api/v1/staff/appointments/901/schedule-support',
    '/api/v1/staff/appointments', '/api/v1/staff/appointments/901/changes', '/api/v1/staff/appointments/901/cancellations',
    '/api/v1/staff/appointments/901/arrival-verifications', '/api/v1/staff/appointments/901/no-show-verifications',
  ])
  for (const [, options] of calls.slice(2)) {
    assert.equal(options.credentials, 'same-origin')
    assert.equal(options.headers['Idempotency-Key'], 'original-key')
    assert.equal(options.headers['X-CSRF-Token'], 'csrf')
  }
  assert.throws(() => api.write('unknown', '901', {}, 'key', 'csrf'), /INVALID_APPOINTMENT_COMMAND/)
  assert.equal(calls.length, 7)
})
