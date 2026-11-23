import {test,expect} from '@playwright/test'
import {readFile} from 'node:fs/promises'
import {resolve} from 'node:path'
for(const stale of [false,true])test(stale?'stale original selection cannot proceed to save':'reception confirms the locked patient selection without editing its time',async({page})=>{
 const id='00000000-0000-4000-8000-000000000101';let confirms=0,saves=0;
 const scheme={effectiveScheduleRef:{scheduleId:'87001',versionNo:2},startsAt:'2026-11-21T09:00:00+08:00',endsAt:'2026-11-21T09:27:00+08:00',treatmentRequest:'SYNTHETIC 患者选定方案',durationBasis:{schemaVersion:1,basisMode:'INITIAL_CURRENT',primaryProjectId:'85001',projectVersion:1,adoptedDurationMinutes:27}}
 await page.route('**/*',async route=>{const r=route.request(),u=new URL(r.url());if(u.origin!=='https://adc-slots.invalid')return route.abort();
  if(u.pathname==='/staff/appointments'){
   const context={canRead:true,actions:['create'],requestActorScope:'b'.repeat(32),csrfToken:'SYNTHETIC-CSRF',verifiedBookingSource:{requestId:id,patientId:'71004',reference:'SYNTHETIC-course-request:'+id+':1'}};
   const preview={requestId:id,patientId:'71004',preferredDate:'2026-11-21',treatmentNeed:scheme.treatmentRequest,selectedScheme:scheme,patientConfirmed:true};
   return route.fulfill({contentType:'text/html',body:(await readFile('dist/index.html','utf8')).replace('</head>',`<meta name="adc-booking-interaction-enabled" content="true"><meta name="adc-booking-source-preview" content="${Buffer.from(JSON.stringify(preview)).toString('base64')}"><meta name="adc-appointments-context" content='${JSON.stringify(context)}'></head>`)})}
  if(u.pathname.startsWith('/assets/'))return route.fulfill({contentType:u.pathname.endsWith('.css')?'text/css':'text/javascript',body:await readFile(resolve('dist',u.pathname.slice(1)))})
  if(u.pathname==='/api/v1/staff/booking-confirmations'){confirms++;expect(r.postDataJSON().scheme).toEqual(scheme);return route.fulfill(stale?{status:409,json:{code:'BOOKING_PROPOSAL_STALE'}}:{json:{synthetic:true,patientId:'71004',scheme,acceptanceEvidenceReference:'SYNTHETIC-confirmation:00000000-0000-4000-8000-000000000002'}})}
  if(u.pathname==='/api/v1/staff/appointments'){saves++;return route.fulfill({json:{requestReference:'UC/v1/UC02/'+'b'.repeat(32)+'/APPOINTMENT_CREATE/'+r.headers()['idempotency-key'],ucCode:'UC02',operationKind:'APPOINTMENT_CREATE',auditPersistence:'CONFIRMED',status:'COMPLETED',businessOutcome:'SUCCEEDED',links:{appointmentId:'71005',originalUcSuccessEventId:'71006'}}})}
  return route.abort();
 });
 await page.goto('/staff/appointments?sourceRequest='+id+'&sourceVersion=1');
 await expect(page.getByText('患者已选择并确认以下预约安排。前台核验后办理，不可替换时间；失鲜或约满时请患者重新选择。')).toBeVisible();
 await expect(page.getByRole('button',{name:'查询可用时段',exact:true})).toHaveCount(0);
 await expect(page.getByLabel('开始时间（诊所当地时间）',{exact:true})).toHaveCount(0);
 await page.getByLabel('预约信息核验说明',{exact:true}).fill('SYNTHETIC reception verified the patient selection');
 await page.getByRole('checkbox').check();await page.getByRole('button',{name:'核验并通过预约',exact:true}).click();
 if(stale){await expect(page.getByText('预约安排已变化或被占用，请重新查询并取得申请人确认。')).toBeVisible();await expect(page.getByRole('button',{name:'确认预约',exact:true})).toHaveCount(0);}
 else await expect(page.getByRole('region',{name:'预约成功回执'})).toContainText('71005');
 expect(confirms).toBe(1);expect(saves).toBe(stale?0:1);
 await page.screenshot({path:'../../docs/current-booking-fix-evidence/compat-reception-'+(stale?'stale':'locked')+'.png',fullPage:true});
})
