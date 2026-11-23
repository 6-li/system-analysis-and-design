const root = '/api/v1/staff'
const part = value => encodeURIComponent(String(value))
const versionPath = (id, version) => '/schedules/' + part(id) + '/versions/' + part(version)

export function scheduleApi(fetcher = globalThis.fetch) {
  async function call(path, body, key, csrf) {
    const write = body !== undefined
    try {
      const result = await fetcher(root + path, {
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        ...(write ? {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key, 'X-CSRF-Token': csrf },
          body: JSON.stringify(body),
        } : {}),
      })
      const data = await result.json()
      if (!data || typeof data !== 'object') throw new Error('INVALID_RESPONSE')
      return { http: result.status, data }
    } catch {
      // A network or decoding error cannot prove a write was rolled back.
      return { http: 0, data: { code: 'RESPONSE_UNAVAILABLE' } }
    }
  }
  return {
    impacts: (id, version) => call(versionPath(id, version) + '/impacts'),
    checkImpact: (id, version, key, csrf) => call(versionPath(id, version) + '/impact-checks', null, key, csrf),
    activate: (id, version, data, key, csrf) => call(versionPath(id, version) + '/activations', data, key, csrf),
    activationResult: reference => call('/schedule-commands/' + reference.split('/').map(part).join('/')),
    histories: (id, version) => call(versionPath(id, version) + '/resolution-histories'),
    saveResolution: (id, version, data, key, csrf) =>
      call(versionPath(id, version) + '/resolutions', data, key, csrf),
    list: query => call('/schedules?' + new URLSearchParams(query)),
    version: (id, version) => call('/schedules/' + part(id) + '/versions/' + part(version)),
    issue: id => call('/schedule-maintenance-issues/' + part(id)),
    check: (data, key, csrf) => call('/schedule-checks', data, key, csrf),
    create: (data, key, csrf) => call('/schedule-drafts', data, key, csrf),
    revise: (id, data, key, csrf) => call('/schedules/' + part(id) + '/draft-revisions', data, key, csrf),
    recordIssue: (data, key, csrf) => call('/schedule-maintenance-issues', data, key, csrf),
  }
}

export function resolutionDisposition({ http, data }) {
  if (http === 200 && data.ucCode === 'UC22' && data.operationKind === 'RESOLUTION_SAVE' &&
      data.status === 'COMPLETED' && data.businessOutcome === 'SUCCEEDED' &&
      data.auditPersistence === 'CONFIRMED' && data.requestReference &&
      /^[1-9][0-9]*$/.test(data.links?.resolutionId || '')) return 'saved'
  if (http === 409 && ['RESOLUTION_HEAD_STALE', 'RESOLUTION_APPOINTMENT_STALE', 'RESOLUTION_DRAFT_STALE'].includes(data.code)) return 'stale'
  if (http === 409 && data.code === 'REQUEST_CONFLICT' && typeof data.requestReference === 'string' &&
      data.requestReference.trim()) return 'rejected'
  if (http === 503 && data.code === 'OPERATION_FAILED' && typeof data.requestReference === 'string' &&
      data.requestReference.trim()) return 'rejected'
  if ([400, 401, 403, 404, 422].includes(http) && typeof data.code === 'string') return 'rejected'
  return 'unknown'
}

export function writeDisposition(operation, { http, data }) {
  if (http === 0 || http === 202 || ['COMMIT_OUTCOME_UNKNOWN', 'WORK_ROLLBACK_UNCONFIRMED'].includes(data.code) || data.status === 'UNKNOWN') return 'unknown'
  if (http === 200) {
    if (!data.requestReference || !data.links?.originalUcSuccessEventId) return 'unknown'
    if (operation === 'issue') {
      return data.status === 'PERSISTED_INCOMPLETE' && data.links.issueId && !data.links.scheduleRef ? 'issue' : 'unknown'
    }
    const ids = data.appointmentImpact?.appointmentIds
    return data.status === 'COMPLETED' && data.businessOutcome === 'DRAFT' && data.links.scheduleRef &&
      Array.isArray(ids) && ids.every(id => typeof id === 'string' && /^(0|-?[1-9][0-9]*)$/.test(id)) &&
      new Set(ids).size === ids.length ? 'draft' : 'unknown'
  }
  // An unrecognized intermediary/server error may be hiding a lost response.
  if (http >= 500 && !['DEPENDENCY_UNAVAILABLE', 'OPERATION_FAILED'].includes(data.code)) return 'unknown'
  return 'rejected'
}
