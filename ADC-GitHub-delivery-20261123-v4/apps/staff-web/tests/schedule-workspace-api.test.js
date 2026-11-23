import test from 'node:test'
import assert from 'node:assert/strict'
import { scheduleApi, writeDisposition } from '../src/api/schedules.js'

test('UC21 sends the seven contracted paths, with opaque key and CSRF only on POST', async () => {
  const sent = []
  const api = scheduleApi(async (url, init) => {
    sent.push({ url, init })
    return { status: 200, json: async () => ({}) }
  })
  await api.list({ clinicId: '42' })
  await api.version('9007199254740999', '2')
  await api.issue('3')
  await api.check({ clinicId: '42' }, 'check', 'csrf')
  await api.create({ clinicId: '42' }, 'first', 'csrf')
  await api.revise('9007199254740999', { basisRef: { scheduleId: '9007199254740999', versionNo: 2 }, expectedStateToken: 'fresh', previousIssueId: '19' }, 'revise', 'csrf')
  await api.recordIssue({ submittedContext: { clinicId: '42' }, failureReason: 'reason' }, 'issue', 'csrf')
  assert.deepEqual(sent.map(x => x.url), [
    '/api/v1/staff/schedules?clinicId=42', '/api/v1/staff/schedules/9007199254740999/versions/2',
    '/api/v1/staff/schedule-maintenance-issues/3', '/api/v1/staff/schedule-checks', '/api/v1/staff/schedule-drafts',
    '/api/v1/staff/schedules/9007199254740999/draft-revisions', '/api/v1/staff/schedule-maintenance-issues',
  ])
  assert.ok(sent.every(x => x.init.credentials === 'same-origin' && x.init.redirect === 'error'))
  assert.deepEqual(sent.slice(3).map(x => x.init.headers['Idempotency-Key']), ['check', 'first', 'revise', 'issue'])
  assert.ok(sent.slice(3).every(x => x.init.method === 'POST' && x.init.headers['X-CSRF-Token'] === 'csrf'))
  assert.equal(JSON.parse(sent[5].init.body).previousIssueId, '19')
  assert.equal(JSON.parse(sent[4].init.body).previousIssueId, undefined)
  assert.equal(JSON.parse(sent[6].init.body).previousIssueId, undefined)
})

test('known drafts and incomplete issues have separate receipts and malformed 200 is unsafe', () => {
  const common = { requestReference: 'ref' }
  const draft = { ...common, status: 'COMPLETED', businessOutcome: 'DRAFT', links: { originalUcSuccessEventId: '5', scheduleRef: { scheduleId: '9', versionNo: 1 } }, appointmentImpact: { appointmentIds: ['9007199254740999'] } }
  const issue = { ...common, status: 'PERSISTED_INCOMPLETE', links: { originalUcSuccessEventId: '6', issueId: '10' } }
  assert.equal(writeDisposition('create', { http: 200, data: draft }), 'draft')
  assert.equal(writeDisposition('issue', { http: 200, data: issue }), 'issue')
  assert.equal(writeDisposition('issue', { http: 200, data: draft }), 'unknown')
  assert.equal(writeDisposition('create', { http: 200, data: { ...draft, appointmentImpact: undefined } }), 'unknown')
  assert.equal(writeDisposition('revise', { http: 200, data: { ...draft, appointmentImpact: { appointmentIds: ['3', '3'] } } }), 'unknown')
  assert.equal(writeDisposition('revise', { http: 409, data: { code: 'STALE_VERSION' } }), 'rejected')
})

test('unknown, broken response and transport loss are never replayed by the HTTP client', async () => {
  let count = 0
  const api = scheduleApi(async () => { count++; throw new Error('synthetic loss') })
  assert.equal(writeDisposition('create', await api.create({}, 'stable', 'csrf')), 'unknown')
  assert.equal(count, 1)
  assert.equal(writeDisposition('revise', { http: 202, data: { status: 'UNKNOWN' } }), 'unknown')
  assert.equal(writeDisposition('issue', { http: 503, data: { code: 'COMMIT_OUTCOME_UNKNOWN' } }), 'unknown')
  assert.equal(writeDisposition('issue', { http: 503, data: { code: 'WORK_ROLLBACK_UNCONFIRMED' } }), 'unknown')
  assert.equal(writeDisposition('issue', { http: 503, data: { code: 'DEPENDENCY_UNAVAILABLE' } }), 'rejected')
})
