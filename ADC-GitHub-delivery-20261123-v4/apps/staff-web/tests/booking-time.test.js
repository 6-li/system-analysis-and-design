import test from 'node:test';import assert from 'node:assert/strict';import {localCandidates,offsetText} from '../src/api/booking-time.js'
test('clinic wall time resolves independently of browser zone and rejects DST gap',()=>{assert.deepEqual(localCandidates('2026-12-04T10:10:00','Asia/Shanghai'),['2026-12-04T02:10:00.000Z']);assert.deepEqual(localCandidates('2026-03-08T02:30:00','America/New_York'),[]);assert.equal(localCandidates('2026-11-01T01:30:00','America/New_York').length,2)})

test('scheme endpoints carry the actual clinic offset',()=>assert.equal(offsetText('2026-12-04T02:10:00.000Z','Asia/Shanghai'),'2026-12-04T10:10:00+08:00'))

