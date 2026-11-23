const test=require('node:test'),assert=require('node:assert/strict');
function page(){let def;global.Page=d=>def=d;delete require.cache[require.resolve('../miniprogram/pages/index/index.js')];require('../miniprogram/pages/index/index.js');delete global.Page;return {...def,data:{...def.data,signedIn:true,clinic:'81001',date:'2026-11-22'},setData(d){Object.assign(this.data,d)}}}
test('doctor precedes time selection; filtering retains original estimated end and full status',async()=>{
 const p=page();let reads=0;p.client={bookingOptions:async()=>{reads++;return {projects:[],slots:[{dentistId:'79001',startsAt:'2026-11-22T09:00+08:00',endsAt:'2026-11-22T09:27+08:00',availabilityStatus:'FULL',durationBasis:{adoptedDurationMinutes:27}},{dentistId:'79002'}]}}};
 await p.loadBookingSlots();assert.equal(reads,0);
 p.data.bookingDoctors=[{dentistId:'79001',dentistLabel:'演示牙医甲'}];p.chooseBookingDoctor({detail:{value:'0'}});await p.loadBookingSlots();
 assert.equal(p.data.bookingSlots.length,1);assert.match(p.data.bookingSlots[0].label,/09:00 到诊/);assert.equal(p.data.bookingSlots[0].estimatedEnd,'09:27');assert.equal(p.data.bookingSlots[0].estimatedMinutes,27);
 p.chooseBookingSlot({currentTarget:{dataset:{index:0}}});assert.equal(p.data.selectedSlot,null);
 p.data.selectedSlot={};p.data.selectionConfirmed=true;p.chooseBookingDoctor({detail:{value:'0'}});assert.equal(p.data.selectionConfirmed,false);assert.equal(p.data.selectedSlot,null);
});
test('late doctor-directory response cannot overwrite another clinic',async()=>{
 const p=page();let finish;p.client={bookingOptions:()=>new Promise(r=>finish=r)};const read=p.loadBookingDoctors();p.data.clinic='81002';finish({dentists:[{dentistId:'79002'}]});await read;assert.deepEqual(p.data.bookingDoctors,[]);
});
