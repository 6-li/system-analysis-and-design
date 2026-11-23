import {test,expect} from '@playwright/test'
import {readFile} from 'node:fs/promises'
import {resolve} from 'node:path'
for(const mode of ['minutes','seconds','stale','unknown'])test('one-click original booking: '+mode,async({page})=>{
 const id='00000000-0000-4000-8000-000000000101';let confirms=0,saves=0,key='';
 const precision=mode==='seconds'?':00':'';
 const scheme={effectiveScheduleRef:{scheduleId:'87001',versionNo:2},startsAt:`2026-11-22T09:00${precision}+08:00`,endsAt:`2026-11-22T09:27${precision}+08:00`,treatmentRequest:'牙疼',durationBasis:{schemaVersion:1,basisMode:'INITIAL_CURRENT',primaryProjectId:'85001',projectVersion:1,adoptedDurationMinutes:27}};
 await page.route('**/*',async route=>{const r=route.request(),u=new URL(r.url());if(u.origin!=='https://adc-booking-fix.invalid')return route.abort();
  if(u.pathname==='/staff/appointments'){
   const context={canRead:true,actions:['create'],requestActorScope:'c'.repeat(32),csrfToken:'SYNTHETIC-CSRF',verifiedBookingSource:{requestId:id,patientId:'71004',reference:'SYNTHETIC-course-request:'+id+':1'}};
   const preview={requestId:id,patientId:'71004',preferredDate:'2026-11-22',treatmentNeed:'牙疼',selectedScheme:scheme,patientConfirmed:true};
   return route.fulfill({contentType:'text/html',body:(await readFile('dist/index.html','utf8')).replace('</head>',`<meta name="adc-booking-interaction-enabled" content="true"><meta name="adc-booking-source-preview" content="${Buffer.from(JSON.stringify(preview)).toString('base64')}"><meta name="adc-appointments-context" content='${JSON.stringify(context)}'></head>`)})}
  if(u.pathname.startsWith('/assets/'))return route.fulfill({contentType:u.pathname.endsWith('.css')?'text/css':'text/javascript',body:await readFile(resolve('dist',u.pathname.slice(1)))})
  if(u.pathname==='/api/v1/staff/booking-confirmations'){confirms++;expect(r.postDataJSON().scheme).toEqual(scheme);return route.fulfill(mode==='stale'?{status:409,json:{code:'BOOKING_PROPOSAL_STALE'}}:{json:{synthetic:true,patientId:'71004',scheme,acceptanceEvidenceReference:'SYNTHETIC-confirmation:00000000-0000-4000-8000-000000000002'}})}
  if(u.pathname==='/api/v1/staff/appointments'&&r.method()==='POST'){
   saves++;key=r.headers()['idempotency-key'];expect(r.postDataJSON().scheme).toEqual(scheme);
   if(mode==='unknown')return route.abort('failed');
   return route.fulfill({json:{requestReference:'UC/v1/UC02/'+'c'.repeat(32)+'/APPOINTMENT_CREATE/'+key,ucCode:'UC02',operationKind:'APPOINTMENT_CREATE',auditPersistence:'CONFIRMED',status:'COMPLETED',businessOutcome:'SUCCEEDED',links:{appointmentId:'71005',originalUcSuccessEventId:'71006'}}})
  }
  if(u.pathname==='/api/v1/staff/mini-requests')return route.fulfill({json:{items:[]}});
  return route.abort();
 });
 await page.goto('/staff/appointments?sourceRequest='+id+'&sourceVersion=1');
 await expect(page.getByRole('button',{name:'确认预约',exact:true})).toHaveCount(0);
 await page.getByLabel('预约信息核验说明',{exact:true}).fill('沟通正确，已核对门诊、医生和时间');
 await page.getByRole('checkbox').check();await page.getByRole('button',{name:'核验并通过预约',exact:true}).click();
 if(mode==='stale'){await expect(page.getByText('预约安排已变化或被占用，请重新查询并取得申请人确认。')).toBeVisible();expect(saves).toBe(0);await expect(page.getByRole('region',{name:'预约成功回执'})).toHaveCount(0);}
 else if(mode==='unknown'){
  await expect(page.getByText('提交结果待核对。请保留原请求标识。',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'核验并通过预约',exact:true})).toBeDisabled();expect(saves).toBe(1);
  const stored=await page.evaluate(()=>JSON.parse(sessionStorage.getItem('adc.appointments.pending:'+'c'.repeat(32))));expect(stored.key).toBe(key);await expect(page.getByRole('region',{name:'预约成功回执'})).toHaveCount(0);
 }else{await expect(page.getByRole('region',{name:'预约成功回执'})).toContainText('71005');expect(saves).toBe(1);await expect(page.getByRole('region',{name:'预约成功回执'})).toContainText(scheme.startsAt);}
 expect(confirms).toBe(1);
 await page.screenshot({path:'../../docs/current-booking-fix-evidence/'+mode+'.png',fullPage:true});
});
