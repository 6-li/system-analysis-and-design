import {test,expect} from '@playwright/test'
import {readFile} from 'node:fs/promises'
import {resolve} from 'node:path'
test('selected request carries its date and need into confirmation and a persisted success receipt',async({page})=>{
 const id='00000000-0000-4000-8000-000000000101';let booked=false,posts=0
 await page.route('**/*',async route=>{const r=route.request(),u=new URL(r.url());if(u.origin!=='https://adc-inbox.invalid')return route.abort()
  if(u.pathname==='/staff/appointments'){
   const context={canRead:true,actions:['create','arrival'],requestActorScope:'a'.repeat(32),csrfToken:'SYNTHETIC-CSRF',...(!u.searchParams.has('receipt')?{verifiedBookingSource:{requestId:id,patientId:'71004',reference:'SYNTHETIC-course-request:'+id+':1'}}:{})}
   const preview={requestId:id,patientId:'71004',preferredDate:'2026-11-21',treatmentNeed:'SYNTHETIC applicant need'}
   return route.fulfill({contentType:'text/html',body:(await readFile('dist/index.html','utf8')).replace('</head>',`<meta name="adc-booking-interaction-enabled" content="true"><meta name="adc-mini-inbox-enabled" content="true"><meta name="adc-clinic-zone" content="Asia/Shanghai"><meta name="adc-booking-source-preview" content="${Buffer.from(JSON.stringify(preview)).toString('base64')}"><meta name="adc-appointments-context" content='${JSON.stringify(context)}'></head>`)})
  }
  if(u.pathname.startsWith('/assets/'))return route.fulfill({contentType:u.pathname.endsWith('.css')?'text/css':'text/javascript',body:await readFile(resolve('dist',u.pathname.slice(1)))})
  if(u.pathname==='/api/v1/staff/mini-requests')return route.fulfill({json:{items:booked?[]:[{requestId:id,requestVersion:1,type:'NEW',associatedPatientId:'71004'}]}})
  if(u.pathname==='/api/v1/staff/booking-options'){expect(u.searchParams.get('date')).toBe('2026-11-21');return route.fulfill({json:{synthetic:true,timeZone:'Asia/Shanghai',projects:[{projectId:'85001',projectName:'SYNTHETIC 项目',projectVersion:1,minutes:27}],windows:[{scheduleId:'87001',versionNo:2,dentistId:'79001',startsAt:'2026-11-21T01:00:00Z',endsAt:'2026-11-21T05:00:00Z'}]}})}
  if(u.pathname==='/api/v1/staff/booking-confirmations'){const b=r.postDataJSON();expect(b.patientId).toBe('71004');expect(b.scheme.treatmentRequest).toBe('SYNTHETIC applicant need');return route.fulfill({json:{synthetic:true,patientId:b.patientId,scheme:b.scheme,acceptanceEvidenceReference:'SYNTHETIC-confirmation:00000000-0000-4000-8000-000000000002'}})}
  if(u.pathname==='/api/v1/staff/appointments'&&r.method()==='POST'){posts++;booked=true;return route.fulfill({json:{requestReference:'UC/v1/UC02/'+'a'.repeat(32)+'/APPOINTMENT_CREATE/'+r.headers()['idempotency-key'],ucCode:'UC02',operationKind:'APPOINTMENT_CREATE',auditPersistence:'CONFIRMED',status:'COMPLETED',businessOutcome:'SUCCEEDED',links:{appointmentId:'71002',originalUcSuccessEventId:'71003'}}})}
  return route.abort()
 })
 await page.goto('/staff/appointments?sourceRequest='+id+'&sourceVersion=1')
 await expect(page.getByRole('heading',{name:'办理患者预约申请',exact:true})).toBeVisible()
 await expect(page.getByText('申请核验信息不可用，请返回核验后再办理。',{exact:true})).toHaveCount(0)
 await expect(page.getByLabel('预约日期',{exact:true})).toHaveValue('2026-11-21')
 await expect(page.getByLabel('诊疗需求',{exact:true})).toHaveValue('SYNTHETIC applicant need')
 await expect(page.getByRole('button',{name:'查询预约',exact:true})).toHaveCount(0)
 await page.getByRole('button',{name:'查询可用时段',exact:true}).click()
 await page.getByLabel('诊疗项目',{exact:true}).selectOption('85001')
 await page.getByLabel('牙医及正式时段',{exact:true}).selectOption('87001:2')
 await page.getByLabel('申请人确认核验依据',{exact:true}).fill('SYNTHETIC communicated and accepted')
 await page.getByRole('checkbox').check()
 await page.getByRole('button',{name:'记录预约确认',exact:true}).click()
 await expect(page.getByRole('button',{name:'确认预约',exact:true})).toBeEnabled()
 await page.screenshot({path:'../../docs/request-booking-flow-evidence/request-before-save.png',fullPage:true})
 await page.getByRole('button',{name:'确认预约',exact:true}).click()
 await expect(page.getByRole('region',{name:'预约成功回执'})).toContainText('71002')
 await expect(page.getByRole('region',{name:'小程序待处理申请'})).toContainText('本页没有待处理申请')
 await expect(page.getByRole('button',{name:'确认预约',exact:true})).toHaveCount(0)
 await page.reload()
 await expect(page.getByRole('region',{name:'预约成功回执'})).toContainText('保存时状态：待到诊')
 expect(posts).toBe(1)
 await page.screenshot({path:'../../docs/request-booking-flow-evidence/request-saved.png',fullPage:true})
})


test('doctor calendar shows individual occupied intervals without patient identity',async({page})=>{
 await page.route('**/*',async route=>{const r=route.request(),u=new URL(r.url());if(u.origin!=='https://adc-inbox.invalid'||r.method()!=='GET')return route.abort()
  if(u.pathname==='/staff/my-schedule')return route.fulfill({contentType:'text/html',body:(await readFile('dist/index.html','utf8')).replace('</head>','<meta name="adc-clinic-zone" content="Asia/Shanghai"></head>')})
  if(u.pathname.startsWith('/assets/'))return route.fulfill({contentType:u.pathname.endsWith('.css')?'text/css':'text/javascript',body:await readFile(resolve('dist',u.pathname.slice(1)))})
  if(u.pathname==='/api/v1/staff/my-schedule'){expect(u.searchParams.get('includeAppointments')).toBe('true');return route.fulfill({json:{items:[{scheduleId:'87001',versionNo:2,clinicId:'81001',startsAt:'2026-11-21T01:00:00Z',endsAt:'2026-11-21T05:00:00Z',appointmentCount:1,appointments:[{appointmentId:'71002',startsAt:'2026-11-21T02:00:00Z',endsAt:'2026-11-21T02:27:00Z',statusLabel:'待到诊'}]}]}})}
  return route.abort()
 })
 await page.goto('/staff/my-schedule')
 await expect(page.getByRole('list',{name:'该班次预约'})).toContainText('预约 71002 · 待到诊')
 await expect(page.getByRole('list',{name:'该班次预约'})).toContainText('2026-11-21T10:00:00')
 await page.screenshot({path:'../../docs/request-booking-flow-evidence/doctor-timeline.png',fullPage:true})
})


