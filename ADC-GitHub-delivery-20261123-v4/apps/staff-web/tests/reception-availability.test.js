import test from 'node:test'
import assert from 'node:assert/strict'
import {receptionAvailability} from '../src/services/reception-availability.js'
const at=t=>'2026-11-22T'+t+'+08:00'
test('complete occupancy subtracts cross-store blocks with microsecond precision and isolates dentists',()=>{
 const result=receptionAvailability({coverage:'COMPLETE',windows:[{dentistId:'1',startsAt:at('09:00:00'),endsAt:at('10:00:00')}],busy:[{dentistId:'1',startsAt:at('09:20:00.000001'),endsAt:at('09:30:00')},{dentistId:'2',startsAt:at('09:00:00'),endsAt:at('10:00:00')}]});
 assert.deepEqual(result.filter(x=>x.kind==='FREE').map(x=>[x.startsAt,x.endsAt]),[[at('09:00:00'),at('09:20:00.000001')],[at('09:30:00'),at('10:00:00')]])
})
test('incomplete or malformed occupancy cannot produce free time',()=>{
 assert.throws(()=>receptionAvailability({windows:[],busy:[]}));
 assert.throws(()=>receptionAvailability({coverage:'COMPLETE',windows:[],busy:[{dentistId:'2',startsAt:at('10:00:00'),endsAt:at('09:00:00')}]}));
})
