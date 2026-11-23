const test=require('node:test'),assert=require('node:assert/strict');
const {createHttpClient}=require('../miniprogram/services/http.js');
const {createAppointmentRequests}=require('../miniprogram/services/appointment-requests.js');
const slot={index:0,dentistId:'79001',availabilityStatus:'AVAILABLE',dentistLabel:'SYNTHETIC 牙医',startsAt:'2026-11-21T09:00:00+08:00',endsAt:'2026-11-21T09:27:00+08:00',effectiveScheduleRef:{scheduleId:'87001',versionNo:2},durationBasis:{schemaVersion:1,basisMode:'INITIAL_CURRENT',primaryProjectId:'85001',projectVersion:1,adoptedDurationMinutes:27}};
function page(){let def;global.Page=d=>def=d;delete require.cache[require.resolve('../miniprogram/pages/index/index.js')];require('../miniprogram/pages/index/index.js');delete global.Page;return {...def,data:{...def.data,screen:'booking',signedIn:true,slotSelectionEnabled:true,bookingDoctor:'79001',bookingDoctorName:'SYNTHETIC 牙医',patient:'SYNTHETIC 本人',contact:'self@example.invalid',clinic:'81001',date:'2026-11-21',need:'SYNTHETIC check',bookingSlots:[slot,{...slot,index:1,availabilityStatus:'FULL'}]},setData(d){Object.assign(this.data,d)}}}
function storage(){const map=new Map();global.wx={getRandomValues({success}){success({randomValues:new Uint8Array(16).buffer})},setStorageSync(k,v){map.set(k,v)},removeStorageSync(k){map.delete(k)}};return map;}
test('patient explicitly chooses an available slot; FULL slot cannot replace it; original scheme is submitted',async()=>{
 const p=page();storage();let body;p.client={submit:async(k,b)=>{body=b;return {receiptStatus:'CONFIRMED_RECEIVED',requestId:'SYNTHETIC-request'}}};
 p.chooseBookingSlot({currentTarget:{dataset:{index:0}}});p.chooseBookingSlot({currentTarget:{dataset:{index:1}}});assert.equal(p.data.selectedSlot.index,0);
 p.confirmBookingSelection({detail:{value:['confirmed']}});await p.submit();assert.equal(body.patientConfirmed,true);assert.deepEqual(body.selectedScheme.effectiveScheduleRef,slot.effectiveScheduleRef);assert.equal(body.selectedScheme.startsAt,slot.startsAt);
});
test('stale slot clears acceptance and pending key but retains the patient demand; timeout retains original key',async()=>{
 const p=page();storage();p.data.selectedSlot=slot;p.data.selectionConfirmed=true;p.client={submit:async()=>{throw Object.assign(Error(),{code:'MINI_SELECTED_SLOT_UNAVAILABLE',status:409})}};
 await p.submit();assert.equal(p.data.selectedSlot,null);assert.equal(p.data.pending,'');assert.equal(p.data.need,'SYNTHETIC check');assert.match(p.data.message,/重新查询/);
 p.data.selectedSlot=slot;p.data.selectionConfirmed=true;p.client.submit=async()=>{throw Object.assign(Error(),{code:'TIMEOUT'})};await p.submit();assert.equal(p.data.pending.length,32);assert.match(p.data.message,/原回执/);
});
test('transport allows only the exact new options query and exposes only a safe stale machine code',async()=>{
 let writes=0;const http=createHttpClient({baseUrl:'http://127.0.0.1:12345',allowLocalHttp:true,request(o){writes++;o.success({statusCode:409,data:{code:'MINI_SELECTED_SLOT_UNAVAILABLE',message:'SYNTHETIC private detail'}})}});
 await assert.rejects(http.get('/api/v1/mini/booking-options?clinicId=81001&date=2026-11-21&projectId=85001'),e=>e.code==='MINI_SELECTED_SLOT_UNAVAILABLE'&&!e.message.includes('private'));
 await assert.rejects(http.get('/api/v1/mini/booking-options?clinicId=81001&date=2026-11-21&patientId=72'));assert.equal(writes,1);
});
test('options client preserves FULL instead of inventing availability',async()=>{
 const client=createAppointmentRequests({post:async()=>({accessToken:'SYNTHETIC-token',identityVerified:false}),get:async()=>({clinicId:'81001',date:'2026-11-21',timeZone:'Asia/Shanghai',reservesCapacity:false,projects:[],slots:[{...slot,availabilityStatus:'FULL'}]})});
 await client.login('SYNTHETIC-code');assert.equal((await client.bookingOptions('81001','2026-11-21','85001')).slots[0].availabilityStatus,'FULL');
});
test('changing the date while a query is pending discards the old slot response',async()=>{
 const p=page();let finish;p.client={bookingOptions:()=>new Promise(resolve=>finish=resolve)};const read=p.loadBookingSlots();
 p.edit({currentTarget:{dataset:{field:'date'}},detail:{value:'2026-11-22'}});
 finish({projects:[{projectId:'85001'}],slots:[slot]});await read;assert.equal(p.data.bookingSlots.length,0);assert.equal(p.data.selectedSlot,null);assert.equal(p.data.date,'2026-11-22');
});
test('release configuration requires patient slot selection and preserves the trusted HTTPS endpoint',()=>{
 const vm=require('node:vm'),fs=require('node:fs');let app;
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/app.js'),'utf8'),{App:d=>app=d,require:()=>({backendOrigin:'https://course.example.invalid'}),wx:{getAccountInfoSync:()=>({miniProgram:{envVersion:'release'}})}});
 app.onLaunch();assert.equal(app.globalData.miniRequestConfig.slotSelectionEnabled,true);assert.equal(app.globalData.miniRequestConfig.baseUrl,'https://course.example.invalid');
});
