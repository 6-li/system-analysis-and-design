const test = require('node:test');
const assert = require('node:assert/strict');
const { createHttpClient } = require('../miniprogram/services/http.js');
const { createAppointmentRequests } = require('../miniprogram/services/appointment-requests.js');

test('mini session does not grant patient identity and unknown submission only uses GET to recover', async () => {
  const calls = [];
  const transport = createHttpClient({ baseUrl: 'https://adc.test', request(input) {
    calls.push(input);
    if (input.url.endsWith('/sessions/wechat')) return input.success({ statusCode: 200,
      data: { accessToken: 'SYNTHETIC-session', identityVerified: false } });
    if (input.method === 'POST') return input.fail({ errMsg: 'timeout' });
    return input.success({ statusCode: 200, data: { operation: 'op008', submissionKey: 'SYNTHETIC-new',
      receiptStatus: 'CONFIRMED_RECEIVED', requestId: 'SYNTHETIC-request' } });
  } });
  const client = createAppointmentRequests(transport);
  await assert.rejects(client.submit('SYNTHETIC-new', { type: 'NEW' }), { code: 'INVALID_REQUEST' });
  assert.deepEqual(await client.login('SYNTHETIC-code'), { identityVerified: false });
  assert.equal((await client.submit('SYNTHETIC-new', { type: 'NEW' })).receiptStatus, 'UNKNOWN');
  assert.equal((await client.recover('SYNTHETIC-new')).requestId, 'SYNTHETIC-request');
  assert.deepEqual(calls.map(call => call.method), ['POST', 'POST', 'GET']);
  assert.equal(calls[1].header.Authorization, 'Bearer SYNTHETIC-session');
  assert.equal(calls[1].header['Idempotency-Key'], 'SYNTHETIC-new');
  assert.match(calls[2].url, /submission-receipts\/SYNTHETIC-new\?operation=op008$/);
});
