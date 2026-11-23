const root = '/api/v1/staff/appointments'
const commands = {
  create: ['UC02', 'APPOINTMENT_CREATE', ''],
  change: ['UC03', 'APPOINTMENT_CHANGE', 'changes'],
  cancel: ['UC04', 'APPOINTMENT_CANCEL', 'cancellations'],
  arrival: ['UC05', 'ARRIVAL_VERIFY', 'arrival-verifications'],
  noShow: ['UC06', 'NO_SHOW_VERIFY', 'no-show-verifications'],
}
export const appointmentId = value => typeof value === 'string' && /^-?\d+$/.test(value)
const positive = value => Number.isSafeInteger(value) && value > 0

export function appointmentApi(fetcher = globalThis.fetch) {
  async function call(path, body, key, csrf) {
    try {
      const response = await fetcher(root + path, {
        credentials: 'same-origin', cache: 'no-store', redirect: 'error',
        ...(body === undefined ? {} : {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key, 'X-CSRF-Token': csrf },
          body: JSON.stringify(body),
        }),
      })
      const data = await response.json()
      return { http: response.status, data }
    } catch {
      return { http: 0, data: null }
    }
  }
  return {
    read(id, support = false) {
      if (!appointmentId(id)) throw new Error('INVALID_APPOINTMENT_ID')
      return call('/' + encodeURIComponent(id) + (support ? '/schedule-support' : ''))
    },
    write(action, id, body, key, csrf) {
      if (!Object.hasOwn(commands, action) || (action !== 'create' && !appointmentId(id)) || !key || !csrf)
        throw new Error('INVALID_APPOINTMENT_COMMAND')
      const [, , suffix] = commands[action]
      return call(suffix ? '/' + encodeURIComponent(id) + '/' + suffix : '', body, key, csrf)
    },
  }
}

export function requestReference(action, actor, key) {
  const [uc, operation] = commands[action]
  return `UC/v1/${uc}/${actor}/${operation}/${key}`
}

export function appointmentDisposition(action, { http, data }, reference, id) {
  const [uc, operation] = commands[action]
  if (!data || (data.requestReference !== undefined && data.requestReference !== reference)) return 'unknown'
  if (http === 409 && ['STALE_DURATION_BASIS', 'STALE_APPOINTMENT', 'STALE_SCHEDULE',
    'ACCEPTANCE_RECONFIRMATION_REQUIRED'].includes(data.code)) return 'stale'
  if ([400, 401, 403, 404, 409, 422].includes(http) && typeof data.code === 'string'
      && typeof data.message === 'string' && data.status === undefined) return 'rejected'
  if (http !== 200 || data.requestReference !== reference || data.ucCode !== uc
      || data.operationKind !== operation || data.auditPersistence !== 'CONFIRMED') return 'unknown'
  const links = data.links
  if (!appointmentId(links?.appointmentId) || !appointmentId(links?.originalUcSuccessEventId)
      || (action !== 'create' && links.appointmentId !== id)) return 'unknown'
  if ((action === 'arrival' || action === 'noShow') && data.status === 'PERSISTED_INCOMPLETE'
      && data.businessOutcome === 'PENDING_VERIFICATION' && !Object.hasOwn(links, 'visitId')) return 'pending'
  if (data.status !== 'COMPLETED' || data.businessOutcome !== 'SUCCEEDED'
      || (action === 'arrival' && !appointmentId(links.visitId))
      || (action === 'change' && !appointmentId(links.appointmentChangeId))) return 'unknown'
  return 'saved'
}

export function durationBasis(action, original, proposal) {
  if (!appointmentId(proposal?.primaryProjectId) || proposal.primaryProjectId.startsWith('-')
      || /^0+$/.test(proposal.primaryProjectId)) return null
  if (action === 'change' && (!appointmentId(original?.primaryProjectId)
      || !positive(original.projectVersion) || !positive(original.adoptedDurationMinutes))) return null
  const preserve = action === 'change' && original.primaryProjectId === proposal.primaryProjectId
  const source = preserve ? original : proposal
  if (!positive(source.projectVersion) || !positive(source.adoptedDurationMinutes)) return null
  return {
    schemaVersion: 1, primaryProjectId: source.primaryProjectId, projectVersion: source.projectVersion,
    adoptedDurationMinutes: source.adoptedDurationMinutes,
    basisMode: preserve ? 'PRESERVED_ORIGINAL' : action === 'change' ? 'CHANGED_PROJECT_CURRENT' : 'INITIAL_CURRENT',
  }
}
