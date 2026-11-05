const {before, after, beforeEach, test} = require('node:test');
const {readFileSync} = require('node:fs');
const {createHash} = require('node:crypto');
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

const ticket = '1700000000000-' + 'b'.repeat(20);
test('license events expose only signed public records and reject client writes', async () => {
  const path = 'licenseEvents/' + 'a'.repeat(64);
  const event = {request, value: 'SFLIC3.signed', updatedAt: {'.sv': 'timestamp'}};
  await assertSucceeds(db('owner').ref(path).set(event));
  await assertSucceeds(db('alice').ref(path).once('value'));
  await assertFails(db('alice').ref('licenseEvents').once('value'));
  await assertFails(env.unauthenticatedContext().database().ref(path).once('value'));
  await assertFails(db('alice').ref(path).set(event));
  await assertFails(db('helper').ref(path).set(event));
  await assertFails(db('owner').ref(path).set({...event, name: 'Private name'}));
  await assertFails(db('owner').ref(path).set({...event, request: 'SFREQ2.' + 'b'.repeat(64)}));
  await assertFails(db('owner').ref(path).set({...event, value: 'SFLIC3.' + 'x'.repeat(2048)}));
});

test('license delivery updates private records decisions and events atomically', async () => {
  await assertSucceeds(db('alice').ref('requests/alice').set(body()));
  const id = 'a'.repeat(64), value = 'SFLIC3.revoked';
  const changes = {
    ['devices/' + id]: {request, name: 'Private', description: '', uid: 'alice', value},
    'decisions/alice': {request, state: 'approved', policy: value, decidedAt: {'.sv': 'timestamp'}},
    ['licenseEvents/' + id]: {request, value, updatedAt: {'.sv': 'timestamp'}}
  };
  await assertSucceeds(db('owner').ref().update(changes));
  const event = (await db('alice').ref('licenseEvents/' + id).once('value')).val();
  if (event.value !== value || 'name' in event) throw new Error('Invalid public projection');
  await assertFails(db('alice').ref('devices/' + id).once('value'));
  changes['licenseEvents/' + id].name = 'Must fail';
  changes['devices/' + id].name = 'Must not be committed';
  await assertFails(db('owner').ref().update(changes));
  const saved = (await db('owner').ref('devices/' + id).once('value')).val();
  if (saved.name !== 'Private') throw new Error('Partial publication escaped validation');
});

const messageId = '1700000000001-' + 'c'.repeat(20);
const header = (uid = 'alice') => ({owner: uid, name: 'Người dùng', subject: 'Lỗi kích hoạt',
  type: 'bug', request, appVersion: 'dev', androidVersion: '14',
  createdAt: {'.sv': 'timestamp'}, lastAt: {'.sv': 'timestamp'}});
const message = (uid = 'alice', kind = 'user') => ({uid, name: 'Người gửi', kind,
  text: 'Mô tả lỗi', log: '', createdAt: {'.sv': 'timestamp'}});
const triage = state => ({state, by: 'helper', name: 'Hỗ trợ', changedAt: {'.sv': 'timestamp'}});

async function report(uid = 'alice') {
  await assertSucceeds(db(uid).ref('supportIndex/' + ticket).set(header(uid)));
  const saved = (await db(uid).ref('supportIndex/' + ticket).once('value')).val();
  await assertSucceeds(db(uid).ref('supportUsers/' + uid + '/' + ticket).set({
    subject: saved.subject, type: saved.type, createdAt: saved.createdAt}));
  await assertSucceeds(db(uid).ref('supportMessages/' + ticket + '/' + messageId).set(message(uid)));
}

async function helper(enabled = true) {
  await assertSucceeds(db('owner').ref('supportStaff/helper').set({name: 'Hỗ trợ', enabled}));
}

test('support works without any license and isolates installation inboxes', async () => {
  await assertSucceeds(db('alice').ref('supportIndex/' + ticket).once('value'));
  await report();
  for (const path of ['supportIndex', 'supportIndex/' + ticket, 'supportUsers/alice',
    'supportMessages/' + ticket]) {
    await assertFails(db('bob').ref(path).once('value'));
    await assertFails(env.unauthenticatedContext().database().ref(path).once('value'));
  }
  await assertSucceeds(db('bob').ref('supportUsers/bob').once('value'));
  await assertFails(db('alice').ref('supportIndex').once('value'));
  await assertFails(db('alice').ref('supportIndex/' + ticket + '/subject').set('Changed'));
  await assertSucceeds(db('alice').ref('supportIndex/' + ticket + '/lastAt').set({'.sv': 'timestamp'}));
});

test('suspended expired and rejected users retain support access', async () => {
  for (const state of ['suspended', 'expired', 'rejected']) {
    await env.clearDatabase();
    await env.withSecurityRulesDisabled(async context => {
      await context.database().ref('decisions/alice').set({state, request});
    });
    await report();
  }
});

test('helpers handle every conversation while triage stays invisible to customers', async () => {
  await report(); await helper();
  await assertSucceeds(db('helper').ref('supportIndex').orderByChild('lastAt').limitToLast(21).once('value'));
  await assertSucceeds(db('helper').ref('supportMessages/' + ticket).once('value'));
  for (const state of ['new', 'working', 'need_info', 'resolved']) {
    await assertSucceeds(db('helper').ref('supportStates/' + ticket).set(triage(state)));
    await assertFails(db('alice').ref('supportStates/' + ticket).once('value'));
    await assertFails(db('alice').ref('supportStates').once('value'));
  }
  await assertFails(db('alice').ref('supportStates/' + ticket).set({...triage('resolved'), by: 'alice'}));
  await assertSucceeds(db('helper').ref('supportMessages/' + ticket + '/1700000000002-' + 'd'.repeat(20))
    .set(message('helper', 'staff')));
  await assertSucceeds(db('alice').ref('supportMessages/' + ticket).once('value'));
  await assertFails(db('helper').ref('supportStates/' + ticket).set(triage('invalid')));
});

test('support staff cannot manage licenses administrators or other staff', async () => {
  await report(); await helper();
  for (const path of ['requests', 'decisions/alice', 'devices', 'admins/owner', 'supportStaff']) {
    await assertFails(db('helper').ref(path).once('value'));
  }
  for (const [path, value] of [['admins/helper', true], ['supportStaff/bob', {name: 'Bob', enabled: true}],
    ['decisions/alice', {request, state: 'approved', policy: 'SFLIC3.fake', decidedAt: {'.sv': 'timestamp'}}],
    ['devices/' + 'a'.repeat(64), {request, name: 'A', description: '', value: 'SFLIC3.fake'}]]) {
    await assertFails(db('helper').ref(path).set(value));
  }
  await helper(false);
  await assertFails(db('helper').ref('supportIndex').once('value'));
  await assertFails(db('helper').ref('supportMessages/' + ticket).once('value'));
  await assertFails(db('helper').ref('supportStates/' + ticket).set(triage('working')));
});

test('message authors immutable history attachment limits and schemas are enforced', async () => {
  await report(); await helper();
  const path = 'supportMessages/' + ticket + '/1700000000003-' + 'e'.repeat(20);
  for (const invalid of [{...message(), uid: 'bob'}, {...message(), kind: 'staff'},
    {...message(), log: 'x'.repeat(32001)}, {...message(), text: 'x'.repeat(8001)},
    {...message(), admin: true}, {...message(), text: '', log: ''}, {...message(), createdAt: 0}]) {
    await assertFails(db('alice').ref(path).set(invalid));
  }
  await assertSucceeds(db('alice').ref(path).set({...message(), text: '', log: 'x'.repeat(32000)}));
  await assertFails(db('alice').ref(path).set(message()));
  await assertFails(db('owner').ref(path).remove());
  await assertFails(db('bob').ref(path + '/text').set('Replace'));
  await assertFails(db('alice').ref('supportIndex/' + ticket).remove());
});

test('contacts show event timestamps without leaking the global directory to helpers', async () => {
  await report(); await helper();
  const contact = {request, lastSeen: {'.sv': 'timestamp'}};
  await assertSucceeds(db('alice').ref('supportContacts/alice').set(contact));
  await assertSucceeds(db('helper').ref('supportContacts/alice').once('value'));
  await assertFails(db('helper').ref('supportContacts').once('value'));
  await assertFails(db('bob').ref('supportContacts/alice').once('value'));
  await assertSucceeds(db('owner').ref('supportContacts').orderByChild('request').equalTo(request).once('value'));
  for (const invalid of [{...contact, request: 'SFREQ2.' + 'f'.repeat(64)}, {...contact, lastSeen: 1},
    {...contact, online: true}]) await assertFails(db('alice').ref('supportContacts/alice').set(invalid));
  await assertFails(db('helper').ref('supportContacts/alice').set(contact));
});

const attachmentId = '1700000000009-' + 'f'.repeat(20);
const attachmentPath = () => 'supportAttachments/' + ticket + '/' + attachmentId;
const chunkPath = index => 'supportLogChunks/' + ticket + '/' + attachmentId + '/chunk' + index;
const attachedMessagePath = () => 'supportMessages/' + ticket + '/' + attachmentId;
const attachmentInfo = (bytes, sha256, parts = Math.ceil(bytes / 524288)) => ({
  uid: 'alice', bytes, sha256, parts, createdAt: {'.sv': 'timestamp'}
});

test('a full ten megabyte log uses bounded private chunks and publishes only after upload', async () => {
  await report(); await helper();
  const raw = Buffer.alloc(524288, 'a');
  const hash = createHash('sha256'); for (let i = 0; i < 20; i++) hash.update(raw);
  const sha256 = hash.digest('hex');
  await assertSucceeds(db('alice').ref(attachmentPath()).set(attachmentInfo(10485760, sha256)));
  const summary = {...message(), text: '', logBytes: 10485760, logSha256: sha256};
  await assertFails(db('alice').ref(attachedMessagePath()).set(summary));
  for (let i = 0; i < 20; i++) {
    await assertSucceeds(db('alice').ref(chunkPath(i)).set({index: i, bytes: raw.length, data: raw.toString('base64')}));
  }
  await assertSucceeds(db('alice').ref(attachedMessagePath()).set(summary));
  await assertSucceeds(db('helper').ref(attachmentPath()).once('value'));
  await assertSucceeds(db('helper').ref(chunkPath(19)).once('value'));
  await assertFails(db('bob').ref(attachmentPath()).once('value'));
  await assertFails(db('bob').ref(chunkPath(0)).once('value'));
  await assertFails(env.unauthenticatedContext().database().ref(chunkPath(0)).once('value'));
  await assertFails(db('alice').ref('supportLogChunks/' + ticket + '/' + attachmentId).once('value'));
  await assertFails(db('alice').ref(chunkPath(0)).remove());
  await assertFails(db('alice').ref(chunkPath(0)).set({index: 0, bytes: raw.length, data: raw.toString('base64')}));
  await assertFails(db('alice').ref(attachmentPath()).set(attachmentInfo(10485760, sha256)));
});

test('attachment metadata sizes ownership and chunk schemas reject invalid uploads', async () => {
  await report(); await helper();
  const sha256 = 'a'.repeat(64);
  for (const invalid of [attachmentInfo(10485761, sha256, 21), attachmentInfo(0, sha256, 0),
    attachmentInfo(3, sha256, 2), {...attachmentInfo(3, sha256), uid: 'bob'},
    {...attachmentInfo(3, sha256), extra: true}, attachmentInfo(3, 'invalid')]) {
    await assertFails(db('alice').ref(attachmentPath()).set(invalid));
  }
  await assertSucceeds(db('alice').ref(attachmentPath()).set(attachmentInfo(3, sha256)));
  for (const invalid of [{index: 0, bytes: 4, data: 'YWJj'}, {index: 0, bytes: 3, data: 'x'},
    {index: 1, bytes: 3, data: 'YWJj'}, {index: 0, bytes: 3, data: 'YWJj', extra: true}]) {
    await assertFails(db('alice').ref(chunkPath(0)).set(invalid));
  }
  await assertFails(db('helper').ref(chunkPath(0)).set({index: 0, bytes: 3, data: 'YWJj'}));
  await assertSucceeds(db('alice').ref(chunkPath(0)).set({index: 0, bytes: 3, data: 'YWJj'}));
  await assertFails(db('alice').ref(attachedMessagePath()).set({...message(), logBytes: 3, logSha256: 'b'.repeat(64)}));
  await assertFails(db('alice').ref(attachedMessagePath()).set({...message(), logBytes: 3}));
  await helper(false);
  await assertFails(db('helper').ref(attachmentPath()).once('value'));
  await assertFails(db('helper').ref(chunkPath(0)).once('value'));
});

test('support owners receive only their public status and staff reply markers', async () => {
  await report(); await helper();
  await assertSucceeds(db('helper').ref('supportStates/' + ticket).set(triage('resolved')));
  const state = (await db('helper').ref('supportStates/' + ticket).once('value')).val();
  const index = 'supportUsers/alice/' + ticket;
  await assertSucceeds(db('helper').ref(index).update({status: 'resolved', statusAt: state.changedAt,
    lastAt: {'.sv': 'timestamp'}}));
  const visible = (await db('alice').ref(index).once('value')).val();
  if (visible.status !== 'resolved' || 'by' in visible || 'name' in visible) throw new Error('Private staff data leaked');
  await assertFails(db('bob').ref(index).once('value'));
  await assertFails(db('alice').ref('supportStates').once('value'));
  await assertSucceeds(db('alice').ref('supportUsers/alice').orderByChild('lastAt').limitToLast(100).once('value'));
  await assertSucceeds(db('alice').ref(index).update({lastAt: {'.sv': 'timestamp'}}));
  await assertFails(db('alice').ref(index).update({status: 'working', statusAt: state.changedAt}));
  await assertFails(db('helper').ref(index).update({status: 'working', statusAt: state.changedAt}));
  await assertFails(db('helper').ref(index).update({status: 'resolved', statusAt: state.changedAt + 1}));
});

test('staff reply markers must refer to an actual staff reply in the same conversation', async () => {
  await report(); await helper();
  const replyId = '1700000000002-' + 'd'.repeat(20);
  const index = 'supportUsers/alice/' + ticket;
  await assertFails(db('helper').ref(index).update({lastReplyId: messageId,
    lastReplyAt: {'.sv': 'timestamp'}, lastAt: {'.sv': 'timestamp'}}));
  await assertSucceeds(db('helper').ref('supportMessages/' + ticket + '/' + replyId).set(message('helper', 'staff')));
  await assertSucceeds(db('helper').ref(index).update({lastReplyId: replyId,
    lastReplyAt: {'.sv': 'timestamp'}, lastAt: {'.sv': 'timestamp'}}));
  await assertFails(db('alice').ref(index).update({lastReplyId: replyId,
    lastReplyAt: {'.sv': 'timestamp'}}));
  await helper(false);
  await assertFails(db('helper').ref(index).update({lastReplyId: replyId,
    lastReplyAt: {'.sv': 'timestamp'}}));
  await assertFails(db('helper').ref('supportStates').once('value'));
});
