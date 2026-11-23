import {test,expect} from '@playwright/test';
test('Vue renders without automatic checks or business claims',async({page})=>{let requests=0;page.on('request',r=>{if(r.url().includes('/api/technical/ping'))requests++});await page.goto('/');await expect(page.getByRole('heading',{name:'开发工作台'})).toBeVisible();await expect(page.getByRole('status')).toHaveText('尚未检查');await expect(page.getByText('当前仅提供环境检查。预约、患者资料及收费功能尚未开放。')).toBeVisible();expect(requests).toBe(0)});
test('real browser -> Vite -> Spring Boot technical link',async({page})=>{await page.goto('/');const responsePromise=page.waitForResponse(r=>r.url().endsWith('/api/technical/ping'));await page.getByRole('button',{name:'检查后端连接'}).click();const response=await responsePromise;expect(response.status()).toBe(200);expect(await response.json()).toEqual({service:'adc-backend',status:'UP'});await expect(page.getByRole('status')).toHaveText('技术服务连接正常')});
test('pending component disables duplicate clicks and recovers',async({page})=>{let release;const gate=new Promise(resolve=>release=resolve);let count=0;await page.route('**/api/technical/ping',async route=>{count++;await gate;await route.fulfill({json:{service:'adc-backend',status:'UP'}})});await page.goto('/');await page.getByRole('button').click();await expect(page.getByRole('button',{name:'检查中…'})).toBeDisabled();release();await expect(page.getByRole('status')).toHaveText('技术服务连接正常');await expect(page.getByRole('button')).toBeEnabled();expect(count).toBe(1)});
for(const scenario of ['http','network','malformed','null'])test(`component safely handles ${scenario}`,async({page})=>{await page.route('**/api/technical/ping',async route=>{if(scenario==='network')return route.abort('failed');if(scenario==='http')return route.fulfill({status:503,json:{status:503,code:'HTTP_503',message:'SECRET_INTERNAL'}});if(scenario==='null')return route.fulfill({json:null});return route.fulfill({contentType:'application/json',body:'not-json SECRET_INTERNAL'})});await page.goto('/');await page.getByRole('button').click();await expect(page.getByRole('status')).toHaveText('暂时无法连接技术服务，请检查后端是否启动');await expect(page.getByRole('button')).toBeEnabled();await expect(page.locator('body')).not.toContainText('SECRET_INTERNAL')});
test('unknown route returns home using Vue router',async({page})=>{await page.goto('/does-not-exist');await expect(page.getByRole('heading',{name:'页面不存在'})).toBeVisible();await page.getByRole('link',{name:'返回工作台'}).click();await expect(page.getByRole('heading',{name:'开发工作台'})).toBeVisible()});
test('technical proxy preserves backend safe error envelope',async({request})=>{const response=await request.post('/api/technical/ping',{data:{secret:'DO_NOT_EXPOSE'}});expect(response.status()).toBe(405);expect(response.headers()['cache-control']).toContain('no-store');const body=await response.json();expect(body).toEqual({status:405,code:'HTTP_405',message:'请求无法处理'});expect(JSON.stringify(body)).not.toContain('DO_NOT_EXPOSE')});
test('real fetch deadline returns safe timeout and releases component',async({page})=>{
 await page.route('**/api/technical/ping',()=>new Promise(()=>{}));
 await page.goto('/');await page.getByRole('button').click();
 await expect(page.getByRole('status')).toHaveText('连接超时，请稍后重试',{timeout:8000});
 await expect(page.getByRole('button')).toBeEnabled();
});
test('manual recovery after error uses one new request and the real backend',async({page})=>{
 let requests=0;
 await page.route('**/api/technical/ping',async route=>{
  requests++;
  if(requests===1)return route.fulfill({status:503,json:{code:'HTTP_503',message:'PRIVATE_DETAIL'}});
  return route.continue();
 });
 await page.goto('/');await page.getByRole('button').click();
 await expect(page.getByRole('status')).toHaveText('暂时无法连接技术服务，请检查后端是否启动');
 expect(requests).toBe(1);
 await page.getByRole('button').click();
 await expect(page.getByRole('status')).toHaveText('技术服务连接正常');
 expect(requests).toBe(2);await expect(page.locator('body')).not.toContainText('PRIVATE_DETAIL');
});
