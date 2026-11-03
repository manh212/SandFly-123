const {before, after, beforeEach, test} = require('node:test');
const {readFileSync} = require('node:fs');
const {initializeTestEnvironment, assertFails, assertSucceeds} = require('@firebase/rules-unit-testing');
let env;
const request = 'SFREQ2.' + 'a'.repeat(64);
const body = () => ({request, name: 'Người dùng', description: 'Đăng ký điện thoại', createdAt: {'.sv': 'timestamp'}});
const db = uid => env.authenticatedContext(uid).database();

before(async () => {
  env = await initializeTestEnvironment({projectId: 'demo-meridian-licensing', database: {
    host: '127.0.0.1', port: 9000, rules: readFileSync('database.rules.json', 'utf8')
  }});
});
after(async () => { if (env) await env.cleanup(); });
beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('admins/owner').set(true);
  });
});

test('anonymous visitors cannot read or create requests', async () => {
  const guest = env.unauthenticatedContext().database();
  await assertFails(guest.ref('requests').once('value'));
  await assertFails(guest.ref('requests/alice').set(body()));
});

test('users create and read only their own immutable request', async () => {
  await assertSucceeds(db('alice').ref('requests/alice').set(body()));
  await assertSucceeds(db('alice').ref('requests/alice').once('value'));
  await assertFails(db('bob').ref('requests/alice').once('value'));
  await assertFails(db('alice').ref('requests').once('value'));
  await assertFails(db('bob').ref('requests/alice').set(body()));
  await assertFails(db('alice').ref('requests/alice/name').set('Tên khác'));
  await assertFails(db('alice').ref('requests/alice').remove());
});

test('clients cannot approve themselves or become administrators', async () => {
  await assertSucceeds(db('alice').ref('requests/alice').set(body()));
  await assertFails(db('alice').ref('admins/alice').set(true));
  await assertFails(db('owner').ref('admins/alice').set(true));
  await assertFails(db('alice').ref('decisions/alice').set({request, state: 'approved', policy: 'SFLIC3.fake', decidedAt: {'.sv': 'timestamp'}}));
  await assertFails(db('alice').ref('devices/' + 'a'.repeat(64)).set({request, name: 'x', description: 'x', value: 'x'}));
});

test('owner sees private requests and issues decisions visible only to their recipient', async () => {
  await assertSucceeds(db('alice').ref('requests/alice').set(body()));
  await assertSucceeds(db('owner').ref('requests').once('value'));
  await assertSucceeds(db('owner').ref('decisions/alice').set({request, state: 'approved', policy: 'SFLIC3.signed', decidedAt: {'.sv': 'timestamp'}}));
  await assertSucceeds(db('alice').ref('decisions/alice').once('value'));
  await assertFails(db('bob').ref('decisions/alice').once('value'));
  await assertFails(db('owner').ref('decisions/bob').set({request, state: 'approved', policy: 'SFLIC3.signed', decidedAt: {'.sv': 'timestamp'}}));
});

test('malformed, incomplete, oversized and extra fields are denied', async () => {
  for (const invalid of [ {...body(), name: ''}, {...body(), description: ''},
    {...body(), name: 'a'.repeat(81)}, {...body(), description: 'a'.repeat(1001)},
    {...body(), request: 'SFREQ2.bad'}, {...body(), admin: true}, {...body(), createdAt: 0}]) {
    await assertFails(db('alice').ref('requests/alice').set(invalid));
  }
});

test('device names and descriptions are accessible only to the owner', async () => {
  const path = 'devices/' + 'a'.repeat(64);
  await assertSucceeds(db('owner').ref(path).set({request, name: 'Người dùng', description: 'Thông tin riêng', value: 'SFLIC3.signed'}));
  await assertSucceeds(db('owner').ref('devices').once('value'));
  await assertFails(db('alice').ref(path).once('value'));
  await assertFails(env.unauthenticatedContext().database().ref(path).once('value'));
});
