import {test,expect} from '@playwright/test'
import {readFile} from 'node:fs/promises'
import {resolve} from 'node:path'
test('personal dentist calendar opens current week and displays only appointments',async({page})=>{
 await page.clock.install({time:new Date('2026-11-21T10:00:00+08:00')});
 await page.route('**/*',async route=>{const u=new URL(route.request().url());
  if(u.origin!=='https://adc-calendar.invalid')return route.abort();
  if(u.pathname==='/staff/my-schedule')return route.fulfill({contentType:'text/html',body:(await readFile('dist/index.html','utf8')).replace('</head>','<meta name="adc-clinic-zone" content="Asia/Shanghai"></head>')});
  if(u.pathname.startsWith('/assets/'))return route.fulfill({contentType:u.pathname.endsWith('.css')?'text/css':'text/javascript',body:await readFile(resolve('dist',u.pathname.slice(1)))});
  if(u.pathname==='/api/v1/staff/my-schedule')return route.fulfill({json:{dentistId:'79001',clinicId:'81001',items:[{scheduleId:'1',versionNo:1,clinicId:'81001',startsAt:'2026-11-08T09:00+08:00',endsAt:'2026-11-08T12:00+08:00',appointmentCount:0,appointments:[]},{scheduleId:'2',versionNo:1,clinicId:'81001',startsAt:'2026-11-22T09:00+08:00',endsAt:'2026-11-22T12:00+08:00',appointmentCount:1,appointments:[{appointmentId:'12',startsAt:'2026-11-22T09:00+08:00',endsAt:'2026-11-22T09:27+08:00',statusLabel:'待到诊'}]}]}});
  return route.abort();
 });
 await page.goto('/staff/my-schedule');await expect(page.getByRole('heading',{name:'我的预约日程'})).toBeVisible();
 await expect(page.locator('.calendar-event')).toHaveCount(1);await expect(page.locator('.calendar-event')).toContainText('预约');await expect(page.locator('.calendar-event.effective')).toHaveCount(0);await expect(page.locator('.week-head')).toContainText('2026-11-22');
 await page.screenshot({path:'../../docs/doctor-calendar-update-evidence/dentist.png',fullPage:true});
});
test('reception calendar distinguishes persisted appointments from remaining free intervals',async({page})=>{
 await page.route('**/*',async route=>{const u=new URL(route.request().url());if(u.origin!=='https://adc-calendar.invalid')return route.abort();
  if(u.pathname==='/staff/appointments'){
   const context={canRead:true,actions:['create'],requestActorScope:'c'.repeat(32),csrfToken:'SYNTHETIC-CSRF'};
   return route.fulfill({contentType:'text/html',body:(await readFile('dist/index.html','utf8')).replace('</head>',`<meta name="adc-clinic-zone" content="Asia/Shanghai"><meta name="adc-booking-interaction-enabled" content="true"><meta name="adc-appointments-context" content='${JSON.stringify(context)}'></head>`)});
  }
  if(u.pathname.startsWith('/assets/'))return route.fulfill({contentType:u.pathname.endsWith('.css')?'text/css':'text/javascript',body:await readFile(resolve('dist',u.pathname.slice(1)))});
  if(u.pathname==='/api/v1/staff/booking-calendar')return route.fulfill({json:{timeZone:'Asia/Shanghai',coverage:'COMPLETE',windows:[{dentistId:'79001',startsAt:'2026-11-22T09:00+08:00',endsAt:'2026-11-22T12:00+08:00'}],busy:[{dentistId:'79001',appointmentId:'12',statusLabel:'已预约',startsAt:'2026-11-22T09:00+08:00',endsAt:'2026-11-22T09:27+08:00'}]}});
  if(u.pathname==='/api/v1/staff/mini-requests')return route.fulfill({json:{items:[]}});return route.abort();
 });
 await page.clock.install({time:new Date('2026-11-21T10:00:00+08:00')});await page.goto('/staff/appointments');await page.getByText('查看医生日程与空闲时间',{exact:true}).click();
 await expect(page.locator('.calendar-event.appointment')).toHaveCount(1);await expect(page.locator('.calendar-event.free')).toHaveCount(1);await expect(page.locator('.calendar-event.free')).toContainText('09:27');await expect(page.locator('.calendar-event.free')).toContainText('12:00');
 await page.screenshot({path:'../../docs/reception-calendar-service-evidence/reception.png',fullPage:true});
});
