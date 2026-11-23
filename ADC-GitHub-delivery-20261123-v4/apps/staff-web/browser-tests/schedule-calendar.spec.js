import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const bundle = resolve('dist')
test('read-only calendars display manager states, personal schedules and only the authorized appointment', async ({ page }) => {
  const writes = []
  const entries = ['EFFECTIVE','DRAFT','SUPERSEDED'].map((status,i) => ({
    ref: { scheduleId: String(87001+i), versionNo: 2 }, clinicId: '81001', dentistId: i===1?'79002':'79001',
    startsAt: `2026-11-16T${String(9+i).padStart(2,'0')}:00:00Z`, endsAt: `2026-11-16T${String(12+i).padStart(2,'0')}:00:00Z`,
    activationStatus: status, statusLabel: status,
  }))
  const ref = entries[0].ref
  const support = { appointmentId:'901',originScheduleRef:ref,result:'SUPPORTED',effectiveScheduleRef:ref }
  await page.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url())
    if (request.method() !== 'GET') { writes.push(request.method()); return route.abort() }
    if (url.origin !== 'https://adc-calendar.invalid') return route.abort()
    if (url.pathname.startsWith('/staff/')) {
      const context = JSON.stringify({canRead:true,actions:[]})
      const html = (await readFile(resolve(bundle,'index.html'),'utf8')).replace('</head>',
        `<meta name="adc-clinic-zone" content="Etc/UTC"><meta name="adc-appointments-context" content='${context}'></head>`)
      return route.fulfill({contentType:'text/html',body:html})
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType:url.pathname.endsWith('.css')?'text/css':'text/javascript',body:await readFile(resolve(bundle,url.pathname.slice(1)))})
    if (url.pathname==='/api/v1/staff/schedules') return route.fulfill({json:{items:entries,nextCursor:'87003:2'}})
    if (url.pathname==='/api/v1/staff/my-schedule') return route.fulfill({json:{items:[{
      scheduleId:'87001',versionNo:2,clinicId:'81001',startsAt:entries[0].startsAt,endsAt:entries[0].endsAt,appointmentCount:2}]}})
    if (url.pathname==='/api/v1/staff/appointments/901/schedule-support') return route.fulfill({json:support})
    if (url.pathname==='/api/v1/staff/appointments/901') return route.fulfill({json:{appointmentId:'901',
      stateToken:'SYNTHETIC',originScheduleRef:ref,currentSupport:support,statusLabel:'待到诊',
      startsAt:entries[0].startsAt,endsAt:'2026-11-16T09:27:00Z'}})
    return route.abort()
  })
  await page.goto('/staff/schedule-workspace')
  await page.getByRole('button',{name:'查询安排',exact:true}).click()
  const calendar=page.getByRole('region',{name:'诊所每周排班'})
  await expect(calendar.locator('.calendar-event')).toHaveCount(1)
  await expect(calendar.getByLabel('安排状态')).toHaveValue('EFFECTIVE')
  await calendar.getByLabel('安排状态').selectOption('')
  await expect(calendar.locator('.calendar-event')).toHaveCount(3)
  await expect(calendar).toContainText('这些卡片不代表同时接诊的患者')
  await expect(calendar).toContainText('仅展示已加载记录')
  await calendar.getByLabel('安排状态').selectOption('DRAFT')
  await expect(calendar.locator('.calendar-event')).toHaveCount(1)
  await calendar.locator('.calendar-event').click()
  await expect(calendar).toContainText('待生效草案不能用于正式预约')
  await calendar.getByLabel('安排状态').selectOption('')
  await calendar.locator('.calendar-event.effective').click()
  await expect(page.getByLabel('安排编号',{exact:true})).toHaveValue('87001')
  await expect(page.getByText('已选择安排，请读取指定版本后再核对；点击日历不会修改排班。')).toBeVisible()
  await calendar.screenshot({path:'../../docs/schedule-calendar-evidence/manager-calendar.png'})
  await calendar.getByRole('button',{name:'下一周',exact:true}).click()
  await expect(calendar.locator('.calendar-event')).toHaveCount(0)
  await calendar.getByRole('button',{name:'上一周',exact:true}).click()
  await expect(calendar.locator('.calendar-event')).toHaveCount(3)
  await page.goto('/staff/my-schedule')
  await expect(page.locator('.calendar-event')).toHaveCount(1)
  await page.locator('.calendar-event').click()
  await expect(page.locator('.calendar-detail')).toContainText('累计占用预约 2笔，不代表同时接诊人数')
  await page.goto('/staff/appointments')
  await page.getByLabel('预约编号',{exact:true}).fill('901')
  await page.getByRole('button',{name:'查询预约',exact:true}).click()
  await expect(page.locator('.calendar-event')).toHaveCount(1)
  await expect(page.getByText('这里只展示当前查询的预约，不是诊所全部排班或可预约时段。')).toBeVisible()
  expect(writes).toEqual([])
})
