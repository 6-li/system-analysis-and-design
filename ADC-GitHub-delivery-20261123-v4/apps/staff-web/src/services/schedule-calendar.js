// Read-only projection. Pixel positions must never be used for availability/conflict decisions.
const dayMs = 86400000
function stamp(date) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('INVALID_CALENDAR_DATE')
  const value = Date.parse(date + 'T00:00:00Z')
  if (!Number.isFinite(value) || new Date(value).toISOString().slice(0, 10) !== date) throw new Error('INVALID_CALENDAR_DATE')
  return value
}
export function addCalendarDays(date, count) { return new Date(stamp(date) + count * dayMs).toISOString().slice(0, 10) }
export function calendarWeek(date) {
  const start = addCalendarDays(date, -((new Date(stamp(date)).getUTCDay() + 6) % 7))
  return Array.from({ length: 7 }, (_, index) => addCalendarDays(start, index))
}
function local(value, zone) {
  if (typeof value !== 'string' || !/(Z|[+-]\d{2}:\d{2})$/.test(value) || !Number.isFinite(Date.parse(value))) {
    throw new Error('INVALID_ABSOLUTE_TIME')
  }
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-GB', { timeZone: zone, hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', timeZoneName: 'shortOffset' })
    .formatToParts(new Date(value)).map(part => [part.type, part.value]))
  const fraction = (value.match(/\.(\d{1,6})(?:Z|[+-]\d{2}:\d{2})$/)?.[1] || '').padEnd(6, '0')
  return { date: `${parts.year}-${parts.month}-${parts.day}`,
    micros: BigInt(Date.parse(value)) * 1000n + BigInt(fraction.slice(3) || '0'),
    minute: Number(parts.hour) * 60 + Number(parts.minute) + Number(parts.second) / 60 + Number(fraction) / 60000000,
    label: `${parts.hour}:${parts.minute} ${parts.timeZoneName}`, offset: parts.timeZoneName }
}
export function calendarDate(value, zone) { return local(value, zone).date }
export function calendarProjection(entries, zone, anchor) {
  if (!zone) throw new Error('CALENDAR_ZONE_REQUIRED')
  new Intl.DateTimeFormat('en-GB', { timeZone: zone }).format(0)
  const dates = calendarWeek(anchor)
  const days = dates.map(date => ({ date, slots: [] }))
  let rejected = 0
  for (const [index, entry] of entries.entries()) {
    try {
      const start = local(entry.startsAt, zone), end = local(entry.endsAt, zone)
      if (end.micros <= start.micros) throw new Error('INVALID_WINDOW')
      const status = entry.kind === 'FREE' ? 'FREE' : entry.kind === 'APPOINTMENT' ? 'APPOINTMENT'
        : ['DRAFT', 'EFFECTIVE', 'SUPERSEDED'].includes(entry.activationStatus) ? entry.activationStatus : 'UNKNOWN'
      for (const day of days) {
        if (day.date < start.date || day.date > end.date) continue
        const from = day.date === start.date ? start.minute : 0
        const to = day.date === end.date ? end.minute : 1440
        // End is exclusive: a midnight end does not occupy the next calendar day.
        if (to === 0 && day.date === end.date && day.date !== start.date) continue
        day.slots.push({ ...entry, key: `${index}:${day.date}`, status,
          startMinute: from, endMinute: Math.max(from, to),
          startsLabel: start.label, endsLabel: end.label, offsetChanged: start.offset !== end.offset,
          refLabel: entry.ref ? `${entry.ref.scheduleId} / ${entry.ref.versionNo}`
            : entry.scheduleId ? `${entry.scheduleId} / ${entry.versionNo}` : '',
          statusText: status === 'FREE' ? '空闲' : status === 'APPOINTMENT' ? (entry.statusLabel || '预约记录')
            : ({ DRAFT: '待生效草案', EFFECTIVE: '已生效', SUPERSEDED: '历史版本', UNKNOWN: '待核对' })[status] })
      }
    } catch { rejected++ }
  }
  for (const day of days) {
    day.slots.sort((a, b) => a.startMinute - b.startMinute || a.endMinute - b.endMinute || a.key.localeCompare(b.key))
    // Independent lanes keep concurrent dentists/versions visible; no merging or resource conclusions.
    const ends = []
    for (const slot of day.slots) {
      let lane = ends.findIndex(end => end <= slot.startMinute)
      if (lane < 0) lane = ends.length
      ends[lane] = Math.max(slot.endMinute, slot.startMinute + 30)
      slot.lane = lane
    }
    for (const slot of day.slots) slot.lanes = Math.max(1, ends.length)
  }
  const slots = days.flatMap(day => day.slots)
  const startHour = slots.length ? Math.max(0, Math.floor(Math.min(...slots.map(slot => slot.startMinute)) / 60) - 1) : 8
  const endHour = slots.length ? Math.min(24, Math.ceil(Math.max(...slots.map(slot => Math.max(slot.endMinute, slot.startMinute + 30))) / 60) + 1) : 18
  return { days, rejected, startHour, endHour: Math.max(startHour + 1, endHour) }
}
