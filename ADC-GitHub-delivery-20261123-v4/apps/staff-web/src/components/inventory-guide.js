export function inventoryGuide(raw) {
  try {
    if (!raw) return null
    const value=JSON.parse(atob(raw))
    return value.synthetic===true && value.version==='SYNTHETIC inventory v1' &&
      typeof value.occurredAt==='string' && Number.isFinite(Date.parse(value.occurredAt)) ? value : null
  } catch { return null }
}
