import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const { paths, components } = read('../../../specs/002-dentist-scheduling/contracts/api.openapi.json');
const fixtures = read('../../../backend/src/test/resources/p0/uc21-synthetic.json');
const receipt = components.schemas.CommandReceipt;
const response = async (name, status) => {
  assert.equal(fixtures.scope, 'SYNTHETIC_TEST_ONLY');
  const mock = Response.json(fixtures[name], { status });
  return { code: mock.status, body: await mock.json() };
};

test('T022 draft receipt has exact version identity without an effective outcome', async () => {
  const { code, body } = await response('draftReceipt', 200);
  assert.equal(paths['/staff/schedule-drafts'].post.responses[code].content['application/json'].schema.$ref,
    '#/components/schemas/CommandReceipt');
  assert.equal(body.status, 'COMPLETED');
  assert.equal(body.businessOutcome, 'DRAFT');
  assert.equal(body.links.scheduleRef.scheduleId, '9007199254740993');
  assert.equal(body.links.scheduleRef.versionNo, 1);
  assert.ok(receipt.required.every((key) => Object.hasOwn(body, key)));
});

test('T022 persisted incomplete receipt has an issue identity and no arrangement link', async () => {
  const { code, body } = await response('issueReceipt', 200);
  const schema = paths['/staff/schedule-maintenance-issues'].post.responses[code].content['application/json'].schema;
  assert.ok(schema.allOf[1].properties.status.enum.includes(body.status));
  assert.equal(body.links.issueId, '2001');
  assert.equal(Object.hasOwn(body.links, 'scheduleRef'), false);
});

test('T022 lost response and unaudited failure never supply success links', async () => {
  const unknown = await response('unknownReceipt', 202);
  const failure = await response('unauditedFailure', 200);
  const condition = paths['/staff/schedule-drafts'].post.responses[unknown.code]
    .content['application/json'].schema.allOf[1];
  assert.ok(condition.properties.status.enum.includes(unknown.body.status));
  const noLinks = receipt.oneOf.find((part) => part.properties.status.enum.includes('FAILED'));
  assert.ok(noLinks.not.required.includes('links'));
  assert.equal(unknown.body.auditPersistence, 'UNKNOWN');
  assert.equal(failure.body.auditPersistence, 'NOT_PERSISTED');
  assert.equal(failure.body.status, 'FAILED');
  assert.ok([unknown, failure].every(({ body }) => !Object.hasOwn(body, 'links')));
});

test('T022 stale and denied mocks use the existing safe error shape', async () => {
  const errors = [{ status: 409, code: 'STALE_SYNTHETIC' }, { status: 403, code: 'DENIED_SYNTHETIC' }];
  const schema = components.schemas.Error;
  assert.equal(schema.additionalProperties, false);
  for (const { status, code } of errors) {
    const mock = Response.json({ code, message: '合成错误', requestReference: 'SYNTHETIC/draft/4' }, { status });
    const body = await mock.json();
    assert.equal(paths['/staff/schedules/{scheduleId}/draft-revisions'].post.responses[mock.status]
      .content['application/json'].schema.$ref, '#/components/schemas/Error');
    assert.ok(schema.required.every((key) => Object.hasOwn(body, key)));
    assert.ok(Object.keys(body).every((key) => Object.hasOwn(schema.properties, key)));
  }
});

test.todo('T022 bind mocked replies to the future workspace and verify visible states, duplicate clicks and late reply ordering');
