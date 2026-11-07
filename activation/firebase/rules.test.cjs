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

test('owner cleanup may delete support content but never license data or administrator role', async () => {
  const id = '1700000000000-' + 'e'.repeat(20);
  await env.withSecurityRulesDisabled(async context => {
    const root = context.database().ref();
    await root.child('supportIndex/' + id).set({owner:'alice',subject:'Sửa lỗi',createdAt:1,lastAt:1});
    await root.child('supportMessages/' + id + '/' + id).set({text:'Báo lỗi',kind:'user'});
    await root.child('supportAttachments/' + id + '/' + id).set({uid:'alice',bytes:10,parts:1,createdAt:1,sha256:'a'.repeat(64)});
    await root.child('supportLogChunks/' + id + '/' + id + '/chunk0').set({index:0,bytes:10,data:'AAAA'});
    await root.child('devices/' + 'a'.repeat(64)).set({request,name:'x',description:'x',value:'signed'});
  });
  await assertFails(db('alice').ref('supportAttachments/' + id + '/' + id).remove());
  await assertFails(db('helper').ref('supportLogChunks/' + id).remove());
  await assertSucceeds(db('owner').ref('supportLogChunks/' + id + '/' + id).remove());
  await assertSucceeds(db('owner').ref('supportAttachments/' + id + '/' + id).remove());
  await assertSucceeds(db('owner').ref('supportMessages/' + id).remove());
  await assertSucceeds(db('owner').ref('supportIndex/' + id).remove());
  await assertFails(db('owner').ref('admins/owner').remove());
  await assertFails(db('owner').ref('devices/' + 'a'.repeat(64)).remove());
  await assertFails(db('owner').ref('licenseEvents/' + 'a'.repeat(64)).remove());
});

test('cleanup cannot remove requests that already have a decision', async () => {
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('requests/alice').set(body());
    await context.database().ref('decisions/alice').set({request,state:'approved',policy:'SFLIC3.signed',decidedAt:1});
    await context.database().ref('requests/bob').set(body());
  });
  await assertFails(db('owner').ref('requests/alice').remove());
  await assertSucceeds(db('owner').ref('requests/bob').remove());
  await assertFails(db('alice').ref('requests/alice').remove());
});

test('forum root deletion is denied and scoped cleanup stays owner-only', async () => {
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('forumPosts/topic').set({post:'saved'});
  });
  await assertFails(db('alice').ref('forumPosts/topic').remove());
  await assertFails(db('alice').ref('forumPosts/topic2').set({post:'new'}));
  await assertFails(db('owner').ref('forumPosts').remove());
});


test('forum reports can be filed without exposing the moderation inbox', async () => {
  const topicId = '1700000000000-' + 'a'.repeat(20);
  const reportId = '1700000000001-' + 'b'.repeat(20);
  await assertSucceeds(db('alice').ref('forumIdentity/alice').set({request, fingerprint: 'a'.repeat(64)}));
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('forumTopics/' + topicId).set({
      uid: 'bob', category: 'general', title: 'Nội dung thảo luận',
      body: 'Nội dung cần kiểm tra', locked: false, createdAt: 1, lastAt: 1
    });
  });
  const path = 'forumReports/' + topicId + '/' + reportId;
  const payload = {uid: 'alice', post: '', createdAt: {'.sv': 'timestamp'}};
  await assertSucceeds(db('alice').ref(path).set(payload));
  await assertFails(db('alice').ref(path).once('value'));
  await assertFails(db('bob').ref(path).once('value'));
  await assertFails(db('alice').ref('forumReports').once('value'));
  await assertSucceeds(db('owner').ref('forumReports').once('value'));
  await assertFails(db('alice').ref(path).remove());
  await assertFails(db('alice').ref(path).set(payload));
  await assertSucceeds(db('owner').ref(path).remove());
  await assertFails(db('alice').ref(path).set({...payload, uid:'bob'}));
  await assertFails(db('alice').ref('supportMessages').once('value'));
  await assertFails(db('alice').ref('devices').once('value'));
});

const fid = suffix => String(Date.now()).padStart(13, '0') + '-' + suffix.repeat(20);
const forumIdentity = fingerprint => ({request: 'SFREQ2.' + fingerprint, fingerprint});
const forumTopic = uid => ({uid, category: 'general', title: 'Chủ đề kiểm tra', body: 'Bài viết thử nghiệm',
  createdAt: {'.sv': 'timestamp'}, lastAt: {'.sv': 'timestamp'}, rankAt: {'.sv': 'timestamp'}, locked: false});

async function forumSetup() {
  await assertSucceeds(db('alice').ref('forumIdentity/alice').set(forumIdentity('b'.repeat(64))));
  await assertSucceeds(db('bob').ref('forumIdentity/bob').set(forumIdentity('c'.repeat(64))));
  const topic = fid('a');
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('forumTopics/' + topic).set({uid: 'alice', category: 'general',
      title: 'Bài học chung', body: 'Câu hỏi trong diễn đàn', locked: false, createdAt: 1, lastAt: 1, rankAt: 1});
    await context.database().ref('forumMigration/ready').set(true);
  });
  return topic;
}

test('forum visitors can read bounded topic pages but cannot browse private users', async () => {
  const topic = await forumSetup();
  await assertSucceeds(db('bob').ref('forumTopics').orderByChild('lastAt').limitToLast(30).once('value'));
  await assertFails(db('bob').ref('forumTopics').once('value'));
  await assertSucceeds(db('bob').ref('forumTopics/' + topic).once('value'));
  await assertFails(db('bob').ref('requests/alice').once('value'));
  await assertFails(db('bob').ref('forumIdentity/alice').once('value'));
  await assertFails(db('bob').ref('forumNameHistory').once('value'));
  await assertFails(db('bob').ref('forumLegacyNames').once('value'));
});

test('members see private chat only for their UID and moderators never read it', async () => {
  await forumSetup();
  const topic = fid('d');
  await assertSucceeds(db('alice').ref('forumChats/alice/messages/' + topic)
    .set({from:'alice',text:'Xin chào',createdAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('alice').ref('forumChats/alice/messages').once('value'));
  await assertSucceeds(db('owner').ref('forumChats/alice/messages').once('value'));
  await assertFails(db('bob').ref('forumChats/alice/messages').once('value'));
  await assertFails(db('helper').ref('forumChats/alice/messages').once('value'));
  await assertFails(db('bob').ref('forumChatIndex').once('value'));
  await assertFails(db('bob').ref('forumChats/alice/messages/' + fid('e')).set({from:'bob',text:'X',createdAt:{'.sv':'timestamp'}}));
});

test('polls accept only one vote per UID and never allow later changes', async () => {
  const topic = await forumSetup();
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('forumTopics/' + topic).update({pollOptions:['A','B','C'],pollClosed:false});
  });
  const path = 'forumPollVotes/' + topic + '/bob';
  const guard = 'forumPollGuards/' + topic + '/' + 'c'.repeat(64);
  await assertFails(db('bob').ref(path).set({choice:1,createdAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bob').ref(guard).set({uid:'bob',choice:1,createdAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bob').ref(path).set({choice:1,createdAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bob').ref(guard).once('value'));
  await assertFails(db('alice').ref(guard).once('value'));
  await assertFails(db('alice').ref('forumPollGuards').once('value'));
  await assertFails(db('bob').ref(path).set({choice:2,createdAt:{'.sv':'timestamp'}}));
  await assertFails(db('bob').ref(path).remove());
  await assertFails(db('bob').ref('forumPollVotes/' + topic + '/alice').set({choice:1,createdAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('alice').ref('forumTopics/' + topic + '/pollClosed').set(true));
  await assertFails(db('alice').ref('forumPollVotes/' + topic + '/alice').set({choice:1,createdAt:{'.sv':'timestamp'}}));
});

test('a second UID on the same device cannot cast a second ballot', async () => {
  const topic = await forumSetup();
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('forumTopics/' + topic)
      .update({pollOptions:['A','B'],pollClosed:false});
  });
  const fingerprint = 'c'.repeat(64);
  const guard = 'forumPollGuards/' + topic + '/' + fingerprint;
  await assertSucceeds(db('bob').ref(guard)
    .set({uid:'bob',choice:1,createdAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bob').ref('forumPollVotes/' + topic + '/bob')
    .set({choice:1,createdAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bobFresh').ref('forumIdentity/bobFresh')
    .set(forumIdentity(fingerprint)));
  await assertFails(db('bobFresh').ref(guard)
    .set({uid:'bobFresh',choice:0,createdAt:{'.sv':'timestamp'}}));
  await assertFails(db('bobFresh').ref('forumPollVotes/' + topic + '/bobFresh')
    .set({choice:0,createdAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bobFresh').ref(guard).once('value'));
});

test('blocked members cannot write votes likes or new chats', async () => {
  const topic = await forumSetup();
  await assertSucceeds(db('owner').ref('forumBans/bob').set(true));
  await assertFails(db('bob').ref('forumTopicLikes/' + topic + '/bob')
    .set({likedAt:{'.sv':'timestamp'}}));
  await assertFails(db('bob').ref('forumChats/bob/messages/' + fid('f'))
    .set({from:'bob',text:'Tin mới',createdAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bob').ref('forumTopics/' + topic).once('value'));
});

test('guest quota is bounded to three slots per content kind using Vietnam day', async () => {
  await forumSetup();
  const day = Math.floor((Date.now() + 25200000) / 86400000);
  for (let slot = 0; slot < 3; slot++) {
    const item = fid(String(slot));
    await assertSucceeds(db('bob').ref('forumDailySlots/' + 'c'.repeat(64) + '/' + day + '/topics/' + slot)
      .set({id:item,uid:'bob',day,reservedAt:{'.sv':'timestamp'}}));
    await assertFails(db('bob').ref('forumDailySlots/' + 'c'.repeat(64) + '/' + day + '/topics/' + slot)
      .set({id:fid('b'),uid:'bob',day,reservedAt:{'.sv':'timestamp'}}));
  }
  await assertFails(db('bob').ref('forumDailySlots/' + 'c'.repeat(64) + '/' + day + '/topics/3')
    .set({id:fid('e'),uid:'bob',day,reservedAt:{'.sv':'timestamp'}}));
  await assertFails(db('bob').ref('forumDailySlots/' + 'c'.repeat(64) + '/' + (day-1) + '/topics/0')
    .set({id:fid('e'),uid:'bob',day:day-1,reservedAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bob').ref('forumDailySlots/' + 'c'.repeat(64) + '/' + day + '/posts/0')
    .set({id:fid('e'),uid:'bob',day,reservedAt:{'.sv':'timestamp'}}));
});

test('only the owner can appoint moderators and license version binds the role', async () => {
  await forumSetup();
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('requests/alice').set(body());
    await context.database().ref('decisions/alice').set({request, state:'approved', policy:'SFLIC3.signed', decidedAt:55});
    await context.database().ref('devices/' + 'a'.repeat(64)).set({request,
      name:'Người dùng',description:'Thiết bị',value:'SFLIC3.signed'});
  });
  const path = 'forumModerators/alice';
  const role = {enabled:true,expiresAt:0,decisionAt:55,
    device:'a'.repeat(64),policy:'SFLIC3.signed'};
  await assertFails(db('alice').ref(path).set(role));
  await assertFails(db('bob').ref(path).set(role));
  await assertSucceeds(db('owner').ref(path).set(role));
  await assertFails(db('bob').ref('forumModerators/alice').once('value'));
  await assertSucceeds(db('alice').ref(path).once('value'));
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('decisions/alice/decidedAt').set(56);
  });
  await assertFails(db('alice').ref(path).once('value'));
  await assertFails(db('alice').ref('forumPinnedTopics/' + fid('a')).set(true));
});

test('reported content can be hidden without making archived material public', async () => {
  const topic = await forumSetup();
  const post = fid('e');
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('forumPosts/' + topic + '/' + post)
      .set({uid:'alice',text:'Nội dung cần ẩn',parent:'',mention:'',createdAt:1});
  });
  const archive = 'forumModerationArchive/' + topic + '/' + post;
  await assertSucceeds(db('owner').ref(archive)
    .set({content:{uid:'alice',text:'Nội dung cần ẩn',parent:'',mention:'',createdAt:1},
      hiddenAt:{'.sv':'timestamp'},by:'owner'}));
  await assertSucceeds(db('owner').ref('forumPosts/' + topic + '/' + post).remove());
  await assertFails(db('bob').ref(archive).once('value'));
  await assertSucceeds(db('owner').ref(archive).once('value'));
});


test('moderator role becomes unusable after the device policy changes', async () => {
  const topic = await forumSetup();
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('requests/alice').set(body());
    await context.database().ref('decisions/alice').set({request, state:'approved',
      policy:'SFLIC3.signed',decidedAt:80});
    await context.database().ref('devices/' + 'a'.repeat(64)).set({request,
      name:'Người dùng',description:'Thiết bị',value:'SFLIC3.signed'});
  });
  await assertSucceeds(db('owner').ref('forumModerators/alice').set({enabled:true,expiresAt:0,
    device:'a'.repeat(64),policy:'SFLIC3.signed',decisionAt:80}));
  await assertSucceeds(db('alice').ref('forumPinnedTopics/' + topic).set(true));
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('devices/' + 'a'.repeat(64) + '/value').set('SFLIC3.new-policy');
  });
  await assertFails(db('alice').ref('forumModerators/alice').once('value'));
  await assertFails(db('alice').ref('forumPinnedTopics/' + topic).remove());
});

test('a member cannot reorder topics without their own new reply', async () => {
  const topic = await forumSetup();
  await assertFails(db('bob').ref('forumTopics/' + topic + '/lastAt')
    .set({'.sv':'timestamp'}));
  const day = Math.floor((Date.now() + 25200000) / 86400000);
  const post = fid('1');
  await assertSucceeds(db('bob').ref('forumDailySlots/' + 'c'.repeat(64) + '/' + day + '/posts/0')
    .set({id:post,uid:'bob',day,reservedAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bob').ref('forumPosts/' + topic + '/' + post)
    .set({uid:'bob',text:'Tôi vừa bình luận',parent:'',mention:'',
      createdAt:{'.sv':'timestamp'},quotaDay:day,quotaSlot:0}));
  const saved = (await db('bob').ref('forumPosts/' + topic + '/' + post).once('value')).val();
  await assertSucceeds(db('bob').ref('forumTopics/' + topic).update({lastAt:saved.createdAt,lastPost:post,rankAt:saved.createdAt}));
  await assertFails(db('alice').ref('forumTopics/' + topic + '/lastAt')
    .set({'.sv':'timestamp'}));
});

test('chat index points only to a real message from the updater', async () => {
  await forumSetup();
  const message = fid('2');
  await assertFails(db('bob').ref('forumChatIndex/bob').set({from:'bob',
    lastAt:{'.sv':'timestamp'},lastMessage:message}));
  await assertSucceeds(db('bob').ref('forumChats/bob/messages/' + message)
    .set({from:'bob',text:'Tin nhắn thử',createdAt:{'.sv':'timestamp'}}));
  const stored = (await db('bob').ref('forumChats/bob/messages/' + message).once('value')).val();
  await assertSucceeds(db('bob').ref('forumChatIndex/bob').set({from:'bob',
    lastAt:stored.createdAt,lastMessage:message}));
  await assertFails(db('alice').ref('forumChatIndex/bob').once('value'));
  await assertFails(db('bob').ref('forumChatIndex/bob').set({from:'bob',
    lastAt:stored.createdAt+1000,lastMessage:message}));
});

test('editing requires a matching owner-only history snapshot', async () => {
  const topic = await forumSetup();
  const original = (await db('alice').ref('forumTopics/' + topic).once('value')).val();
  await assertFails(db('alice').ref('forumTopics/' + topic).update({
    title:'Tiêu đề mới',body:'Nội dung mới',editedAt:{'.sv':'timestamp'}}));
  const event = fid('3');
  const history = 'forumEditAudit/' + topic + '/topic/' + event;
  await assertFails(db('bob').ref(history).set({content:original,
    editedAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('alice').ref(history).set({content:original,
    editedAt:{'.sv':'timestamp'}}));
  await assertFails(db('bob').ref(history).once('value'));
  await assertSucceeds(db('owner').ref(history).once('value'));
  await assertSucceeds(db('alice').ref('forumTopics/' + topic).update({
    title:'Tiêu đề mới',body:'Nội dung mới',editedAt:{'.sv':'timestamp'},editEvent:event}));
  await assertFails(db('alice').ref('forumTopics/' + topic + '/body')
    .set('Nội dung đã đổi nhưng không lưu lịch sử'));
});

test('same installation cannot obtain new daily slots after an anonymous UID reset', async () => {
  await forumSetup();
  const day = Math.floor((Date.now() + 25200000) / 86400000);
  const fingerprint = 'c'.repeat(64);
  const path = 'forumDailySlots/' + fingerprint + '/' + day + '/topics/0';
  await assertSucceeds(db('bob').ref(path).set({id:fid('4'),uid:'bob',day,
    reservedAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bobFresh').ref('forumIdentity/bobFresh')
    .set(forumIdentity(fingerprint)));
  await assertFails(db('bobFresh').ref(path).set({id:fid('5'),uid:'bobFresh',day,
    reservedAt:{'.sv':'timestamp'}}));
  await assertSucceeds(db('bobFresh').ref('forumDailySlots/' + fingerprint + '/' + day + '/topics/1')
    .set({id:fid('6'),uid:'bobFresh',day,reservedAt:{'.sv':'timestamp'}}));
});

test('only current moderators and owner can see or change ratings', async () => {
  const topic = await forumSetup();
  const path = 'forumAdminRatings/' + topic;
  const rating = stars => ({stars, by:'owner', updatedAt:{'.sv':'timestamp'}});
  await assertFails(db('bob').ref(path).once('value'));
  await assertFails(db('bob').ref(path).set({stars:5,by:'bob',updatedAt:{'.sv':'timestamp'}}));
  await assertFails(db('bob').ref('forumPublicRoles/bob').set({badge:'developer'}));
  await assertSucceeds(db('owner').ref(path).set(rating(5)));
  await assertSucceeds(db('owner').ref(path).once('value'));
  await assertFails(db('alice').ref(path).once('value'));
  await assertFails(db('bob').ref('forumTopics/' + topic + '/rankAt').set(1000000));
  await assertSucceeds(db('owner').ref('forumTopics/' + topic + '/rankAt')
    .set(1 + 2 * 604800000));
  await assertSucceeds(db('bob').ref('forumTopics')
    .orderByChild('rankAt').limitToLast(30).once('value'));
  await assertSucceeds(db('owner').ref(path).set(rating(1)));
  await assertFails(db('bob').ref('forumTopics/' + topic + '/rankAt')
    .set(1 - 2 * 604800000));
  await assertSucceeds(db('owner').ref('forumTopics/' + topic + '/rankAt')
    .set(1 - 2 * 604800000));
  await assertFails(db('owner').ref(path).set({stars:6,by:'owner',updatedAt:{'.sv':'timestamp'}}));
});

test('moderator badge requires live license and cannot be forged', async () => {
  await forumSetup();
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('requests/alice').set(body());
    await context.database().ref('decisions/alice').set({request,state:'approved',
      policy:'SFLIC3.signed',decidedAt:80});
    await context.database().ref('devices/' + 'a'.repeat(64)).set({request,
      name:'Người dùng',description:'Thiết bị',value:'SFLIC3.signed'});
  });
  await assertFails(db('alice').ref('forumPublicRoles/alice').set({badge:'moderator'}));
  await assertSucceeds(db('owner').ref('forumModerators/alice').set({enabled:true,expiresAt:0,
    device:'a'.repeat(64),policy:'SFLIC3.signed',decisionAt:80}));
  await assertSucceeds(db('owner').ref('forumPublicRoles/alice').set({badge:'moderator'}));
  await assertSucceeds(db('bob').ref('forumPublicRoles/alice').once('value'));
  await assertFails(db('bob').ref('forumModerators/alice').once('value'));
  await assertSucceeds(db('alice').ref('forumAdminRatings').once('value'));
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('devices/' + 'a'.repeat(64) + '/value').set('SFLIC3.changed');
  });
  await assertFails(db('bob').ref('forumPublicRoles/alice').once('value'));
  await assertFails(db('alice').ref('forumAdminRatings').once('value'));
  await assertSucceeds(db('owner').ref('forumPublicRoles/alice').once('value'));
  await assertFails(db('alice').ref('forumPublicRoles/alice').set({badge:'developer'}));
});

test('legacy public names remain unreadable before owner completes migration', async () => {
  const topic = fid('7');
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('forumTopics/' + topic).set({
      uid:'alice',name:'Tên cũ',category:'general',title:'Chủ đề cũ',body:'Nội dung cũ',
      locked:false,createdAt:1
    });
  });
  await assertFails(db('bob').ref('forumTopics/' + topic).once('value'));
  await assertFails(db('bob').ref('forumTopics').orderByChild('lastAt').limitToLast(30).once('value'));
  await assertSucceeds(db('owner').ref('forumTopics/' + topic).once('value'));
  try {
    await assertSucceeds(db('owner').ref('forumTopics/' + topic).set({
      uid:'alice',category:'general',title:'Chủ đề cũ',body:'Nội dung cũ',
      locked:false,createdAt:1,lastAt:1,rankAt:1
    }));
  } catch (failure) {
    const coverage = await fetch('http://127.0.0.1:9000/.inspect/coverage?ns=demo-meridian-licensing-default-rtdb');
    const dump = await coverage.text();
    console.log('FORUM_MIGRATION_DIAGNOSTIC', coverage.status, dump.length,
      dump.slice(Math.max(0, dump.indexOf('forumTopics') - 500), Math.max(0, dump.indexOf('forumTopics') - 500) + 40000));
    throw failure;
  }
  await assertSucceeds(db('owner').ref('forumMigration/ready').set(true));
  const saved = (await db('bob').ref('forumTopics/' + topic).once('value')).val();
  if ('name' in saved || !('lastAt' in saved)) throw new Error('Legacy name leaked');
});

test('registration entitlement survives an anonymous UID change only through a verified marker', async () => {
  const fingerprint = 'e'.repeat(64);
  const requestText = 'SFREQ2.' + fingerprint;
  await assertSucceeds(db('alice').ref('requests/alice').set({
    request: requestText, name: 'Tên đăng ký', description: 'Thiết bị chính',
    createdAt: {'.sv':'timestamp'}
  }));
  await assertSucceeds(db('alice').ref('forumIdentity/alice').set(forumIdentity(fingerprint)));
  await assertFails(db('alice').ref('forumEnrollmentIndex/' + 'f'.repeat(64)).set(true));
  await assertFails(db('bob').ref('forumEnrollmentIndex/' + fingerprint).set(true));
  await assertSucceeds(db('alice').ref('forumEnrollmentIndex/' + fingerprint).set(true));
  await assertFails(db('alice').ref('forumEnrollmentIndex/' + fingerprint).remove());
  await assertSucceeds(db('alice').ref('forumEnrollmentIndex/' + fingerprint).once('value'));
  await assertFails(db('bob').ref('forumEnrollmentIndex/' + fingerprint).once('value'));
  await assertSucceeds(db('bobFresh').ref('forumIdentity/bobFresh')
    .set(forumIdentity(fingerprint)));
  await assertSucceeds(db('bobFresh').ref('forumEnrollmentIndex/' + fingerprint).once('value'));
  await assertFails(db('bobFresh').ref('requests/alice').once('value'));
  await assertFails(db('bobFresh').ref('forumEnrollmentIndex/' + fingerprint).set(true));
  const day = Math.floor((Date.now() + 25200000) / 86400000);
  await assertFails(db('bobFresh').ref('forumDailySlots/' + fingerprint + '/' + day + '/topics/0')
    .set({id:fid('8'),uid:'bobFresh',day,reservedAt:{'.sv':'timestamp'}}));
});
