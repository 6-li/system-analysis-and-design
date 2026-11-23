function parts(value, zone, options) {
  return new Intl.DateTimeFormat('en-GB', { timeZone: zone, ...options }).formatToParts(new Date(value))
    .reduce((result, part) => ({ ...result, [part.type]: part.value }), {})
}

export function scheduleDays(entries, zone) {
  if (!zone) throw new Error('CLINIC_TIME_ZONE_REQUIRED')
  const days = new Map()
  for (const entry of [...entries].sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt))) {
    const start = parts(entry.startsAt, zone, { year: 'numeric', month: '2-digit', day: '2-digit' })
    const date = `${start.year}-${start.month}-${start.day}`
    const startTime = parts(entry.startsAt, zone, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
    const endTime = parts(entry.endsAt, zone, { year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
    const endDate = `${endTime.year}-${endTime.month}-${endTime.day}`
    if (!days.has(date)) days.set(date, { date, entries: [] })
    days.get(date).entries.push({ ...entry,
      startLabel: `${startTime.hour}:${startTime.minute}`,
      endLabel: `${endDate === date ? '' : endDate + ' '}${endTime.hour}:${endTime.minute}` })
  }
  return [...days.values()]
}
