import {test,expect} from '@playwright/test'
import {readFile} from 'node:fs/promises'
import {resolve} from 'node:path'
test('patient form has separate labels and preserves typed identity on forbidden response',async({page})=>{
 let writes=0
 await page.route('**/*',async route=>{
  const r=route.request(),u=new URL(r.url())
  if(u.origin!=='https://adc-inbox.invalid')return route.abort()
  if(u.pathname==='/staff/patients')return route.fulfill({contentType:'text/html',body:(await readFile('dist/index.html','utf8')).replace('</head>','<meta name="adc-uc01-csrf" content="SYNTHETIC-CSRF"></head>')})
  if(u.pathname.startsWith('/assets/'))return route.fulfill({contentType:u.pathname.endsWith('.css')?'text/css':'text/javascript',body:await readFile(resolve('dist',u.pathname.slice(1)))})
  if(u.pathname==='/api/v1/staff/patients'&&r.method()==='POST'){writes++;return route.fulfill({status:403,json:{code:'FORBIDDEN',message:'患者档案操作未完成'}})}
  return route.abort()
 })
 await page.goto('/staff/patients')
 for(const [name,value] of [['申请编号','00000000-0000-4000-8000-000000000101'],['申请版本','0'],['身份核验依据','SYNTHETIC classroom identity'],['患者姓名','SYNTHETIC new patient'],['患者识别信息','SYNTHETIC classroom new patient'],['联系方式','classroom@example.invalid']])await page.getByLabel(name,{exact:true}).fill(value)
 const layout=await page.locator('fieldset label').evaluateAll(labels=>labels.map(l=>{const input=l.querySelector('input');const text=document.createRange();text.selectNodeContents(l.firstChild);return {label:text.getBoundingClientRect().bottom,input:input.getBoundingClientRect().top}}))
 expect(layout.every(x=>x.input>=x.label)).toBe(true)
 await page.getByRole('button',{name:'核验并建档',exact:true}).click()
 await expect(page.getByRole('status')).toContainText('403 / FORBIDDEN')
 await expect(page.getByLabel('患者识别信息',{exact:true})).toHaveValue('SYNTHETIC classroom new patient')
 expect(writes).toBe(1)
 await page.screenshot({path:'../../docs/patient-intake-ui-repair/forbidden-form.png',fullPage:true})
})
