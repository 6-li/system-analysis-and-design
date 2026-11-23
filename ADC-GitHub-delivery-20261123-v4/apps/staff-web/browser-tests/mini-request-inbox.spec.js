import { test,expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
test('reception inbox selects a stored request without treating submission as a booking',async({page})=>{
 const id='00000000-0000-4000-8000-000000000101',writes=[];let denied=false
 await page.route('**/*',async route=>{
  const request=route.request(),url=new URL(request.url())
  if(request.method()!=='GET'){writes.push(request.method());return route.abort()}
  if(url.origin!=='https://adc-inbox.invalid')return route.abort()
  if(url.pathname.startsWith('/staff/')){
   const context={canRead:true,actions:[]}
   const preview=url.pathname==='/staff/patients'?`<meta name="adc-uc01-source-preview" content="${Buffer.from(JSON.stringify({applicantInformation:"SYNTHETIC 王'同学",patientIdentification:'SYNTHETIC submitted identity',contactInformation:'SYNTHETIC submitted contact'})).toString('base64')}">`:''
   const html=(await readFile(resolve('dist/index.html'),'utf8')).replace('</head>',
    `<meta name="adc-mini-inbox-enabled" content="true"><meta name="adc-uc01-csrf" content="SYNTHETIC-CSRF"><meta name="adc-appointments-context" content='${JSON.stringify(context)}'>${preview}</head>`)
   return route.fulfill({contentType:'text/html',body:html})
  }
  if(/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname))return route.fulfill({contentType:url.pathname.endsWith('.css')?'text/css':'text/javascript',body:await readFile(resolve('dist',url.pathname.slice(1)))})
  if(url.pathname==='/api/v1/staff/mini-requests')return denied?route.fulfill({status:403,json:{code:'INBOX_ACCESS_DENIED'}}):route.fulfill({json:{items:[{
   requestId:id,requestVersion:0,type:'NEW',clinicId:'81001',receivedAt:'2026-11-19T01:00:00Z',applicantSummary:'S***',contactSummary:'S***',preferredDate:'2026-11-20'}]}})
  return route.abort()
 })
 await page.goto('/staff/appointments')
 const inbox=page.getByRole('region',{name:'小程序待处理申请'})
 await expect(inbox).toContainText('新预约申请')
 await expect(inbox).toContainText('提交申请不等于正式预约')
 await inbox.screenshot({path:'../../docs/mini-reception-inbox-evidence/reception-inbox.png'})
 await inbox.getByRole('button',{name:'核验并建档',exact:true}).click()
 await expect(page).toHaveURL(new RegExp('sourceRequest='+id+'&sourceVersion=0'))
 await expect(page.getByLabel('申请编号',{exact:true})).toHaveValue(id)
 await expect(page.getByLabel('申请版本',{exact:true})).toHaveValue('0')
 await expect(page.getByLabel('身份核验依据',{exact:true})).toHaveValue('')
 await expect(page.getByLabel('患者姓名',{exact:true})).toHaveValue('')
 await expect(page.getByLabel('患者识别信息',{exact:true})).toHaveValue('SYNTHETIC submitted identity')
 await expect(page.getByText("申请人自述：SYNTHETIC 王'同学")).toBeVisible()
 denied=true
 await page.getByRole('button',{name:'刷新待办',exact:true}).click()
 await expect(page.getByRole('region',{name:'小程序待处理申请'})).toContainText('暂时无法读取')
 await expect(page.locator('.mini-inbox tbody tr')).toHaveCount(0)
 expect(writes).toEqual([])
 await page.screenshot({path:'../../docs/mini-reception-inbox-evidence/reception-verification.png',fullPage:true})
})
