import {test} from 'node:test';
import assert from 'node:assert/strict';
import {VideoOwnerLedger, validateReservation} from '../src/video-owner-ledger.mjs';

// Simulated durable storage with serialized transactional snapshots. Only the
// TEST fake is in memory. Production MUST use Cloudflare Durable Object storage.
class FakeDurableStorage {
  constructor(backing = new Map()) { this.backing = backing; this.tail = Promise.resolve(); }
  async transaction(fn) {
    const preceding = this.tail;
    let unlock;
    this.tail = new Promise(resolve => { unlock = resolve; });
    await preceding;
    const next = new Map([...this.backing].map(([k,v])=>[k, structuredClone(v)]));
    const tx = {
      async get(key) { return next.get(key); },
      async put(key,value) { next.set(key, structuredClone(value)); },
    };
    try {
      const result = await fn(tx);
      this.backing.clear();
      for(const [k,v] of next) this.backing.set(k,v);
      return result;
    } finally { unlock(); }
  }
}
const NOW=1_795_000_000;
const owner=4145;
let seq=0;
const request=(overrides={})=>({
  ownerId:owner, sessionId:'s_0123456789abcdef0123456789abcdef',
  nonce:'00000000-0000-4000-8000-'+(++seq).toString(16).padStart(12,'0'),
  projectId:'house_1', nowSeconds:NOW, expiresAt:NOW+300,
  maxActive:1, maxPerUtcDay:3, ...overrides,
});

test('Cloudflare-style durable transactions persist reservations across adapter recreation',async()=>{
  const storage=new FakeDurableStorage();
  const first=new VideoOwnerLedger(storage,owner);
  const item=request();
  assert.equal(await first.reserve(item),true);
  const restarted=new VideoOwnerLedger(storage,owner);
  assert.equal(await restarted.reserve(item),false);
  const snapshot=await restarted.stateForVerifiedOwner(owner,NOW);
  assert.equal(snapshot.dailyUsed,1);
  assert.equal(snapshot.active.state,'RESERVED_NOT_DISPATCHED');
  assert.equal(snapshot.active.nonce,item.nonce);
  assert.equal(snapshot.active.ownerId,owner);
});

test('parallel reservations cannot both pass one active-job gate',async()=>{
  const storage=new FakeDurableStorage();
  const ledger=new VideoOwnerLedger(storage,owner);
  const results=await Promise.all(Array.from({length:30},()=>ledger.reserve(request())));
  assert.equal(results.filter(Boolean).length,1);
  assert.equal((await ledger.stateForVerifiedOwner(owner,NOW)).dailyUsed,1);
});

test('owner namespace pinned; cross-user inspection or reservation fails',async()=>{
  const storage=new FakeDurableStorage();
  const allowed=new VideoOwnerLedger(storage,owner);
  assert.equal(await allowed.reserve(request()),true);
  assert.equal((await allowed.stateForVerifiedOwner(owner,NOW)).ownerId,owner);
  assert.throws(()=>validateReservation(request({ownerId:owner+1}),owner),/scope/);
  await assert.rejects(new VideoOwnerLedger(storage,owner+1).stateForVerifiedOwner(owner+1,NOW),/scope/);
  await assert.rejects(new VideoOwnerLedger(storage,owner+1).reserve(request({ownerId:owner+1})),/scope/);
  await assert.rejects(allowed.stateForVerifiedOwner(owner+1,NOW),/another/);
});

test('strict reserve fields and ceiling cannot be bypassed',async()=>{
  const ledger=new VideoOwnerLedger(new FakeDurableStorage(),owner);
  for(const bad of [
    request({ownerId:owner+1}), request({sessionId:'bad'}),
    request({projectId:'../main'}),request({nonce:'reused?'}),
    request({maxActive:2}),request({maxPerUtcDay:999}),
    request({expiresAt:NOW+500}),request({expiresAt:NOW-10}),
    {...request(), accessToken:'evil'},request({nowSeconds:1}),
  ]) await assert.rejects(ledger.reserve(bad));
  assert.equal((await ledger.stateForVerifiedOwner(owner,NOW)).dailyUsed,0);
});

test('per-owner UTC-day quota remains in durable storage even after active is reconciled',async()=>{
  const storage=new FakeDurableStorage();
  const ledger=new VideoOwnerLedger(storage,owner);
  // TEST-ONLY simulated trusted future GitHub terminal reconciler.
  // No such release endpoint/method is available to live users in Slice 10.
  for(let i=0;i<3;i++) {
    assert.equal(await ledger.reserve(request()),true);
    storage.backing.delete('video:v1:active');
  }
  assert.equal(await ledger.reserve(request()),false);
  assert.equal((await ledger.stateForVerifiedOwner(owner,NOW)).dailyUsed,3);
  const tomorrow=NOW+86400;
  assert.equal(await ledger.reserve(request({nowSeconds:tomorrow, expiresAt:tomorrow+300})),true);
  assert.equal((await ledger.stateForVerifiedOwner(owner,tomorrow)).dailyUsed,1);
});

test('no automatic expiry of reserved active job without verified terminal status',async()=>{
  const storage=new FakeDurableStorage();
  const ledger=new VideoOwnerLedger(storage,owner);
  assert.equal(await ledger.reserve(request()),true);
  const tomorrow=NOW+86400;
  assert.equal(await ledger.reserve(request({nowSeconds:tomorrow,expiresAt:tomorrow+300})),false);
  const state=await ledger.stateForVerifiedOwner(owner,tomorrow);
  assert.equal(state.active.state,'RESERVED_NOT_DISPATCHED');
});