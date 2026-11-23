import test from 'node:test'
import assert from 'node:assert/strict'
import {clinicInstant,courseCost} from '../src/components/consumption-time.js'
test('clinic input is converted to Beijing offset without phone timezone dependence',()=>{
 assert.equal(clinicInstant('2026-11-22T15:30'),'2026-11-22T15:30:00+08:00')
 assert.equal(clinicInstant('2026-02-30T15:30'),'')
 assert.equal(clinicInstant('2026-11-22T25:00'),'')
})
test('course cost uses exact decimal quantities and half-up cents',()=>{
 assert.equal(courseCost('1.235'),'2.47');assert.equal(courseCost('2'),'4.00');assert.equal(courseCost('0.001'),'0.00')
 assert.equal(courseCost('0'),'');assert.equal(courseCost('1.0001'),'');assert.equal(courseCost('-1'),'')
})
