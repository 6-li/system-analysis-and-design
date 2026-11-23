import {test,expect} from '@playwright/test'
import {readFile} from 'node:fs/promises'
import {resolve} from 'node:path'
test('arbitrary date selection confirms exact scheme then uses existing create endpoint once',async({page})=>{
 const writes=[]
 await page.route('**/*',async route=>{const r=route.request(),u=new URL(r.url());if(u.origin!=='https://adc-inbox.invalid')return route.abort()
 if(u.pathname==='/staff/appointments'){const context={canRead:true,actions:['create'],requestActorScope:'a'.repeat(32),csrfToken:'SYNTHETIC-CSRF'};return route.fulfill({contentType:'text/html',body:(await readFile('dist/index.html','utf8')).replace('</head>',`<meta name="adc-booking-interaction-enabled" content="true"><meta name="adc-appointments-context" content='${JSON.stringify(context)}'></head>`)})}
 if(u.pathname.startsWith('/assets/'))return route.fulfill({contentType:u.pathname.endsWith('.css')?'text/css':'text/javascript',body:await readFile(resolve('dist',u.pathname.slice(1)))})
 if(u.pathname==='/api/v1/staff/booking-options')return route.fulfill({json:{synthetic:true,timeZone:'Asia/Shanghai',projects:[{projectId:'85001',projectName:'SYNTHETIC 清洁项目',projectVersion:9,minutes:27}],windows:u.searchParams.get('date')==='2026-12-03'?[]:[{scheduleId:'87001',versionNo:2,dentistId:'79001',startsAt:'2026-12-04T01:00:00Z',endsAt:'2026-12-04T05:00:00Z'}]}})
 if(u.pathname==='/api/v1/staff/booking-confirmations'){const b=r.postDataJSON();writes.push(b);expect(b.confirmed).toBe(true);return route.fulfill({json:{synthetic:true,patientId:b.patientId,scheme:b.scheme,acceptanceEvidenceReference:'SYNTHETIC-confirmation:00000000-0000-4000-8000-000000000001'}})}
 if(u.pathname==='/api/v1/staff/appointments'&&r.method()==='POST'){writes.push(r.postDataJSON());return route.fulfill({status:503,json:{code:'COMMIT_OUTCOME_UNKNOWN'}})}
 return route.abort()})
 await page.goto('/staff/appointments')
 await page.getByLabel('患者编号',{exact:true}).fill('71006')
 await page.getByLabel('预约日期',{exact:true}).fill('2026-12-03')
 await page.getByRole('button',{name:'查询可用时段'}).click()
 await expect(page.getByText('这一天没有正式生效的接诊安排',{exact:false})).toBeVisible()
 await page.getByLabel('预约日期',{exact:true}).fill('2026-12-04')
 await page.getByLabel('诊疗需求',{exact:true}).fill('SYNTHETIC treatment')
 await page.getByRole('button',{name:'查询可用时段'}).click()
 await page.getByLabel('诊疗项目',{exact:true}).selectOption('85001')
 await page.getByLabel('牙医及正式时段',{exact:true}).selectOption('87001:2')
 await page.getByLabel('开始时间（诊所当地时间）',{exact:true}).fill('2026-12-04T10:10')
 await page.getByLabel('申请人确认核验依据',{exact:true}).fill('SYNTHETIC simulated patient accepted')
 await page.getByRole('checkbox').check()
 await page.getByRole('button',{name:'记录预约确认'}).click()
 await expect(page.getByRole('button',{name:'提交办理',exact:true})).toBeEnabled()
 await page.getByRole('button',{name:'提交办理',exact:true}).click()
 await expect(page.getByRole('button',{name:'提交办理',exact:true})).toBeDisabled()
 expect(writes).toHaveLength(2)
 expect(writes[1].scheme.startsAt).toBe('2026-12-04T10:10:00+08:00')
 expect(writes[1].scheme.durationBasis.projectVersion).toBe(9)
 expect(writes[1].acceptanceEvidenceReference).toMatch(/^SYNTHETIC-confirmation:/)
 await page.reload()
 await expect(page.getByRole('button',{name:'提交办理',exact:true})).toBeDisabled()
 expect(writes).toHaveLength(2)
 await page.screenshot({path:'../../docs/booking-interactive-evidence/selected-booking.png',fullPage:true})
})



