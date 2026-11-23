const uc21 = require('../../backend/src/test/resources/p0/uc21-synthetic.json')

// Fixture projection for offline tools. These names and records are not a database schema.
function buildDataset() {
  const [clinicId, otherClinicId] = uc21.clinicIds
  const dentistId = uc21.dentistId
  const draft = { ...uc21.draftReceipt.links.scheduleRef }
  const effective = { scheduleId: '87001', versionNo: 2 }
  const duration = { primaryProjectId: '85001', projectVersion: 2, adoptedDurationMinutes: 27 }
  const interval = { startsAt: '2026-11-08T09:00:00.123456Z', endsAt: '2026-11-08T09:27:00.123456Z' }
  const scheme = { patientId: '88001', clinicId, dentistId, effectiveScheduleRef: effective, durationBasis: duration,
    ...interval, treatmentRequest: 'SYNTHETIC-UC02-intent' }
  const self = 'SYNTHETIC-patient-88001'
  const proxy = 'SYNTHETIC-proxy-88001'
  const grant = 'SYNTHETIC-proxy-grant-88001'
  const staff = 'SYNTHETIC-booking-reception'
  const manager = 'SYNTHETIC-clinic-manager'
  const own = { reference: 'SYNTHETIC-acceptance-self', subject: self, scheme }
  const byProxy = { reference: 'SYNTHETIC-acceptance-proxy', subject: proxy, grant, scheme }
  const bookingRequest = 'SYNTHETIC/appointment/901'
  const unknownRequest = uc21.unknownReceipt.requestReference

  return {
    scope: 'SYNTHETIC_TEST_ONLY',
    version: 1,
    clinics: [{ id: clinicId, name: 'SYNTHETIC Clinic A', timeZone: 'Asia/Shanghai' },
      { id: otherClinicId, name: 'SYNTHETIC Clinic B', timeZone: 'Asia/Shanghai' }],
    dentists: [{ id: dentistId, name: 'SYNTHETIC Dentist A', clinicIds: [clinicId, otherClinicId],
      qualificationReference: 'SYNTHETIC-qualification-A' }],
    patients: [{ id: scheme.patientId, subject: self, name: 'SYNTHETIC Patient A',
      contact: 'patient-a@example.invalid' }],
    proxyGrants: [{ reference: grant, subject: proxy, patientId: scheme.patientId, action: 'UC02' }],
    staffBindings: [{ subject: staff, role: 'BOOKING_RECEPTION', action: 'UC02', clinicIds: [clinicId] },
      { subject: manager, role: 'CLINIC_MANAGER', action: 'UC21', clinicIds: [clinicId] }],
    projectVersions: [
      { projectId: duration.primaryProjectId, versionNo: 1, name: 'SYNTHETIC Project A', minutes: 27, status: 'SUPERSEDED' },
      { projectId: duration.primaryProjectId, versionNo: 2, name: 'SYNTHETIC Project A', minutes: 27, status: 'CURRENT' },
    ],
    scheduleVersions: [
      { ...effective, clinicId, dentistId, status: 'EFFECTIVE',
        startsAt: '2026-11-08T09:00:00.123456Z', endsAt: '2026-11-08T11:00:00.123456Z' },
      { ...draft, clinicId, dentistId, status: 'DRAFT',
        startsAt: '2026-11-08T09:00:00.123456Z', endsAt: '2026-11-08T11:00:00.123456Z' },
      { scheduleId: '87002', versionNo: 1, clinicId: otherClinicId, dentistId, status: 'EFFECTIVE',
        startsAt: '2026-11-08T11:00:00.123456Z', endsAt: '2026-11-08T13:00:00.123456Z' },
    ],
    acceptances: [own, byProxy],
    appointments: [{ id: '901', status: '待到诊', ...scheme, acceptanceReference: own.reference, operatedBy: staff }],
    auditEvents: [
      { eventId: '99', requestReference: uc21.draftReceipt.requestReference, sequence: 1, ucCode: 'UC21',
        actor: manager, phase: 'REQUEST_STARTED' },
      { eventId: uc21.draftReceipt.links.originalUcSuccessEventId, requestReference: uc21.draftReceipt.requestReference,
        sequence: 2, ucCode: 'UC21', actor: manager, phase: 'BUSINESS_RESULT', outcome: 'SUCCESS', scheduleRef: draft },
      { eventId: '100', requestReference: bookingRequest, sequence: 1, ucCode: 'UC02', actor: staff, phase: 'REQUEST_STARTED' },
      { eventId: '103', requestReference: bookingRequest, sequence: 2, ucCode: 'UC02', actor: staff,
        phase: 'BUSINESS_RESULT', outcome: 'SUCCESS', appointmentId: '901' },
      { eventId: '104', requestReference: unknownRequest, sequence: 1, ucCode: 'UC21', actor: manager, phase: 'REQUEST_STARTED' },
    ],
    scenarios: {
      patientAcceptance: { acceptanceReference: own.reference },
      representativeAcceptance: { acceptanceReference: byProxy.reference },
      revokedRepresentative: { acceptanceReference: byProxy.reference, revokedGrant: grant },
      staleDuration: { acceptanceReference: own.reference, requestedBasis: { ...duration, projectVersion: 1 } },
      draftNotBookable: { candidateSchedule: draft },
      crossClinicOverlap: { clinicId: otherClinicId, dentistId,
        startsAt: '2026-11-08T10:59:59.123456Z', endsAt: '2026-11-08T11:30:00.123456Z' },
      submittedUnknown: { requestReference: unknownRequest, receipt: { ...uc21.unknownReceipt } },
    },
  }
}

if (require.main === module) process.stdout.write(JSON.stringify(buildDataset(), null, 2) + '\n')

module.exports = { buildDataset }
