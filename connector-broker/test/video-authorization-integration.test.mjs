import {test} from 'node:test';
import assert from 'node:assert/strict';
import {VideoOwnerLedger} from '../src/video-owner-ledger.mjs';
import {verifyGithubOwnerFromServerSession} from '../src/video-github-session.mjs';
import {issueVideoRenderApproval, verifyAndReserveVideoRender} from '../src/video-render-authorization.mjs';

// Test simulation, NOT deployed Durable Object or OAuth endpoint.
class FakeStorage {
  constructor() { this.map=new Map(); this.tail=Promise.resolve(); }
  async transaction(fn) {
    const wait=this.tail;
    let done; this.tail=new Promise(r=>{done=r;});
    await wait;
    const writes=new Map([...this.map].map(([k,v])=>[k,structuredClone(v)]));
    try {
      const result=await fn({
        get:async k=>writes.get(k),
        put:async(k,v)=>{writes.set(k,structuredClone(v));},
      });
      this.map.clear();
      for(const [k,v] of writes) this.map.set(k,v);
      return result;
    } finally {done();}
  }
}
const sessionId='s_0123456789abcdef0123456789abcdef';
const seconds=1_795_000_000;
const secret='test-signing-secret-only-'+ 'A'.repeat(64);
const inputs={project_id:'mini_house_demo',floors:'1',color:'blue',roof:'gable',garden:'no',target:'30'};
const principal=async()=>verifyGithubOwnerFromServerSession({
  sessionId,nowSeconds:seconds,
  vault:{async load(handle){return {kind:'server-issued-github-oauth',id:handle,
    githubUserId:39213,accessToken:'test_server_oauth_token_123456789abc',
    expiresAt:seconds+3600};}},
  fetchImpl:async()=>Response.json({id:39213,login:'verified-user'}),
});

test('OAuth owner -> HMAC approval -> Durable Object reservation; never dispatch',async()=>{
  const owner=await principal();
  const ticket=await issueVideoRenderApproval({inputs,principal:owner,
    explicitApproval:true,signingSecret:secret,nowSeconds:seconds});
  const db=new FakeStorage();
  const ledger=new VideoOwnerLedger(db,owner.githubUserId);
  const decision=await verifyAndReserveVideoRender({ticket,principal:owner,
    signingSecret:secret,nowSeconds:seconds+5,durableReservationStore:ledger,
    verifiedDeployment:true,verifiedActionsPermission:true});
  assert.equal(decision.state,'RESERVED_NOT_DISPATCHED');
  assert.equal(decision.fingerprint,'7a15c74a1779d3aa9ac0ccb6519d8d605a079840f5d25d3b86e0520924c6782e');
  const persisted=new VideoOwnerLedger(db,owner.githubUserId);
  assert.equal((await persisted.stateForVerifiedOwner(owner.githubUserId,seconds)).dailyUsed,1);
  await assert.rejects(verifyAndReserveVideoRender({ticket,principal:owner,
    signingSecret:secret,nowSeconds:seconds+6,durableReservationStore:persisted,
    verifiedDeployment:true,verifiedActionsPermission:true}),/Replay/);
});

test('missing independent deployment or scoped write check must never reserve',async()=>{
  const owner=await principal();
  const ticket=await issueVideoRenderApproval({inputs,principal:owner,
    explicitApproval:true,signingSecret:secret,nowSeconds:seconds});
  const ledger=new VideoOwnerLedger(new FakeStorage(),owner.githubUserId);
  for(const denied of [{verifiedDeployment:false,verifiedActionsPermission:true},
    {verifiedDeployment:true,verifiedActionsPermission:false}]) {
    await assert.rejects(verifyAndReserveVideoRender({ticket,principal:owner,
      signingSecret:secret,nowSeconds:seconds+5,durableReservationStore:ledger,
      ...denied}),/permission|deployment/);
  }
  assert.equal((await ledger.stateForVerifiedOwner(owner.githubUserId,seconds)).dailyUsed,0);
});