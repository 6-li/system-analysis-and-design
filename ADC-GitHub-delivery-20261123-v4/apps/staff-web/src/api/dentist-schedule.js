export async function readDentistSchedule(cursor = '', transport = fetch, includeAppointments = false) {
  if (cursor && !/^[1-9]\d*$/.test(cursor)) throw new Error('INVALID_CURSOR')
  const response = await transport('/api/v1/staff/my-schedule' + (includeAppointments ? '?includeAppointments=true'+(cursor?'&cursor='+encodeURIComponent(cursor):'') : (cursor ? '?cursor=' + encodeURIComponent(cursor) : '')),
    { credentials: 'same-origin', cache: 'no-store', redirect: 'error' })
  const result = await response.json()
  if (!response.ok || !Array.isArray(result.items) || (result.nextCursor != null && !/^[1-9]\d*$/.test(result.nextCursor)) ||
      result.items.some(item => !/^[1-9]\d*$/.test(item.scheduleId) || !/^[1-9]\d*$/.test(item.clinicId) ||
        !Number.isInteger(item.versionNo) || item.versionNo <= 0 || !Number.isSafeInteger(item.appointmentCount) ||
        item.appointmentCount < 0 || !Number.isFinite(Date.parse(item.startsAt)) ||
        !Number.isFinite(Date.parse(item.endsAt)) || Date.parse(item.endsAt) <= Date.parse(item.startsAt))) {
    throw new Error('SCHEDULE_READ_UNAVAILABLE')
  }
  if(includeAppointments && result.items.some(item=>!Array.isArray(item.appointments)||item.appointments.length!==item.appointmentCount||item.appointments.some(a=>!/^[1-9]\d*$/.test(a.appointmentId)||!['待到诊','待核实','已到诊'].includes(a.statusLabel)||!Number.isFinite(Date.parse(a.startsAt))||!Number.isFinite(Date.parse(a.endsAt))||Date.parse(a.startsAt)>=Date.parse(a.endsAt)||Date.parse(a.startsAt)<Date.parse(item.startsAt)||Date.parse(a.endsAt)>Date.parse(item.endsAt))))throw new Error('APPOINTMENT_TIMELINE_UNAVAILABLE')
  return result
}
