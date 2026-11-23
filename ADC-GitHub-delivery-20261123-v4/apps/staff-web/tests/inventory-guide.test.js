import test from 'node:test'
import assert from 'node:assert/strict'
import {inventoryGuide} from '../src/components/inventory-guide.js'
test('only explicit versioned synthetic sources are displayed',()=>{
 const source={synthetic:true,version:'SYNTHETIC inventory v1',occurredAt:'2026-11-22T05:00:00Z'}
 assert.deepEqual(inventoryGuide(btoa(JSON.stringify(source))),source)
 assert.equal(inventoryGuide(btoa(JSON.stringify({...source,synthetic:false}))),null)
 assert.equal(inventoryGuide(btoa(JSON.stringify({...source,version:'unknown'}))),null)
})
test('missing malformed and invalid-time sources do not appear',()=>{
 for(const raw of [undefined,'%%%=',btoa('{}'),btoa(JSON.stringify({synthetic:true,version:'SYNTHETIC inventory v1',occurredAt:'bad'}))])assert.equal(inventoryGuide(raw),null)
})
