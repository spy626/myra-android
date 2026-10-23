/**
 * Slice 16: offline end-to-end staging Worker smoke tests.
 * The real Worker and storage adapters execute against in-memory doubles;
 * ALL GitHub HTTP is replaced with deterministic local responses.
 * This file never makes network calls, deploys, or enables production routes.
 */
import {test} from "node:test";
import assert from "node:assert/strict";
import worker from "../src/video-auth-worker.mjs";
import {VideoOAuthVault} from "../src/video-oauth-vault.mjs";
import {VideoOAuthRateGate} from "../src/video-auth-rate-limit.mjs";

const ORIGIN="https://auth-test.example.org";
const PENDING="__Host-lyra_video_pending";
const SESSION="__Host-lyra_video_session";
const TEST_TOKEN="ghu_offline_smoke_fake_oauth_token_12345678";
const TEST_SECRET="fake_credential_for_local_simulation_123456789";
const fakeJson=data=>Response.json(data,{status:200});

class TransactionalMemory {
  constructor(backing=new Map()){
    this.rows=backing;this.alarmAt=null;this.tail=Promise.resolve();
  }
  async get(k){return structuredClone(this.rows.get(k));}
  async delete(k){this.rows.delete(k);}
  async list({prefix}){return new Map([...this.rows].filter(([k])=>k.startsWith(prefix)).map(([k,v])=>[k,structuredClone(v)]));}
  async getAlarm(){return this.alarmAt;}
  async setAlarm(time){this.alarmAt=time;}
  async transaction(fn){
    let unlock;
    const previous=this.tail;
    this.tail=new Promise(resolve=>{unlock=resolve;});
    await previous;
    const draft=new Map([...this.rows].map(([k,v])=>[k,structuredClone(v)]));
    const tx={
      get:async k=>structuredClone(draft.get(k)),
      put:async(k,v)=>{draft.set(k,structuredClone(v));},
      delete:async k=>{draft.delete(k);},
    };
    try {
      const result=await fn(tx);
      this.rows.clear();
      for(const [k,v] of draft)this.rows.set(k,v);
      return result;
    } finally {unlock();}
  }
}

function fixture(){
  const vaultStorage=new TransactionalMemory();
  const limitStorage=new TransactionalMemory();
  const vault=()=>new VideoOAuthVault({storage:vaultStorage},{VIDEO_SESSION_KEY_HEX:"a".repeat(64)});
  const gate=()=>new VideoOAuthRateGate({storage:limitStorage});
  const env={
    VIDEO_AUTH_ENABLED:"true",VIDEO_AUTH_DEPLOYMENT_APPROVED:"true",
    VIDEO_AUTH_PUBLIC_ORIGIN:ORIGIN,
    VIDEO_GITHUB_CALLBACK_URL:ORIGIN+"/oauth/github/callback",
    VIDEO_GITHUB_CLIENT_ID:"Ov23li_offline_smoke_client",
    VIDEO_GITHUB_CLIENT_SECRET:TEST_SECRET,
    VIDEO_SESSION_KEY_HEX:"a".repeat(64),
    VIDEO_AUTH_RATE_KEY_HEX:"b".repeat(64),
    VIDEO_OAUTH_VAULT:{
      idFromName:name=>name,
      get:()=>vault(),
    },
    VIDEO_AUTH_RATE_GATE:{
      idFromName:name=>name,
      get:()=>gate(),
    },
  };
  let calls=[];
  const github=async (url,options)=>{
    calls.push(String(url));
    assert.equal(options.redirect,"manual");
    if(url==="https://github.com/login/oauth/access_token"){
      assert.equal(options.method,"POST");
      const body=JSON.parse(options.body);
      assert.equal(body.client_id,env.VIDEO_GITHUB_CLIENT_ID);
      assert.equal(body.client_secret,TEST_SECRET);
      assert.equal(body.redirect_uri,env.VIDEO_GITHUB_CALLBACK_URL);
      assert.match(body.code_verifier,/^[A-Za-z0-9_-]{43}$/);
      return fakeJson({access_token:TEST_TOKEN,token_type:"bearer",
        scope:"read:user",expires_in:1200});
    }
    if(url==="https://api.github.com/user"){
      assert.equal(options.method,"GET");
      assert.equal(options.headers.Authorization,"Bearer "+TEST_TOKEN);
      return fakeJson({id:39213,login:"offline-test-user"});
    }
    throw Error("Unapproved external URL was requested");
  };
  function request(path,{method="GET",cookie,origin,site,ip="203.0.113.42"}={}){
    const headers=new Headers({"CF-Connecting-IP":ip});
    if(cookie!==undefined)headers.set("Cookie",cookie);
    if(origin!==undefined)headers.set("Origin",origin);
    if(site!==undefined)headers.set("Sec-Fetch-Site",site);
    return new Request(ORIGIN+path,{method,headers});
  }
  return {env,vault,gate,vaultStorage,limitStorage,request,github,calls};
}
const fromSetCookie=(response,name)=>{
  const entries=typeof response.headers.getSetCookie==="function"
    ? response.headers.getSetCookie()
    : [response.headers.get("set-cookie")||""];
  const match=entries.join("; ").match(new RegExp(name+"=([A-Za-z0-9_-]{32,128})"));
  assert.ok(match,"Missing named secure browser cookie");
  return name+"="+match[1];
};
const stateFrom=res=>{
  assert.equal(res.status,303);
  const url=new URL(res.headers.get("location"));
  assert.equal(url.origin,"https://github.com");
  assert.equal(url.searchParams.get("scope"),"read:user");
  assert.equal(url.searchParams.get("code_challenge_method"),"S256");
  return url.searchParams.get("state");
};
async function localGithub(fn,stub){
  const saved=globalThis.fetch;
  globalThis.fetch=stub;
  try{return await fn();} finally{globalThis.fetch=saved;}
}
const callbackPath=state=>"/oauth/github/callback?state="+encodeURIComponent(state)+
  "&code=sample_code_0123456789";

test("offline browser login callback logout exercises real Worker, vault and limiter",async()=>{
  const f=fixture();
  await localGithub(async()=>{
    const started=await worker.fetch(f.request("/oauth/github/start"),f.env);
    const state=stateFrom(started),pending=fromSetCookie(started,PENDING);
    assert.match(started.headers.get("set-cookie"),/Secure; HttpOnly; SameSite=Lax/);
    assert.equal(started.headers.get("referrer-policy"),"no-referrer");
    const completed=await worker.fetch(f.request(callbackPath(state),{cookie:pending}),f.env);
    assert.equal(completed.status,200);
    assert.deepEqual((await completed.json()).authorized,true);
    const session=fromSetCookie(completed,SESSION);
    const sessionId=session.slice(SESSION.length+1);
    assert.equal((await f.vault().load(sessionId)).githubUserId,39213);
    assert.equal(f.calls.length,2);
    for(const response of [started,completed]){
      assert.ok(!JSON.stringify([...response.headers]).includes(TEST_TOKEN));
      assert.ok(!JSON.stringify([...response.headers]).includes(TEST_SECRET));
    }
    const duplicate=await worker.fetch(f.request(callbackPath(state),{cookie:pending}),f.env);
    assert.equal(duplicate.status,400);
    assert.equal(f.calls.length,2,"replay must not exchange a token");
    const loggedOut=await worker.fetch(f.request("/oauth/github/logout",{
      method:"POST",cookie:session,origin:ORIGIN,site:"same-origin",
    }),f.env);
    assert.equal(loggedOut.status,200);
    assert.equal((await loggedOut.json()).signed_out,true);
    assert.equal(await f.vault().load(sessionId),null);
    assert.ok(!JSON.stringify([...f.vaultStorage.rows]).includes(TEST_TOKEN));
    assert.equal(f.calls.length,2);
  },f.github);
});

test("another browser cannot exchange or revoke a legitimate session",async()=>{
  const f=fixture();
  await localGithub(async()=>{
    const started=await worker.fetch(f.request("/oauth/github/start"),f.env);
    const state=stateFrom(started);
    const wrongCookie=PENDING+"="+"X".repeat(43);
    const wrong=await worker.fetch(f.request(callbackPath(state),{cookie:wrongCookie}),f.env);
    assert.equal(wrong.status,400);
    assert.equal(f.calls.length,0);
    const pending=fromSetCookie(started,PENDING);
    const completed=await worker.fetch(f.request(callbackPath(state),{cookie:pending}),f.env);
    assert.equal(completed.status,200);
    const session=fromSetCookie(completed,SESSION);
    const sessionId=session.slice(SESSION.length+1);
    const forged=await worker.fetch(f.request("/oauth/github/logout",{
      method:"POST",cookie:session,origin:"https://attacker.example.org",site:"cross-site",
    }),f.env);
    assert.equal(forged.status,403);
    assert.ok((await f.vault().load(sessionId))!==null);
    assert.equal(f.calls.length,2);
  },f.github);
});

test("real rate gate rejects fifth start before OAuth state allocation",async()=>{
  const f=fixture();
  for(let i=0;i<4;i++)assert.equal(
    (await worker.fetch(f.request("/oauth/github/start"),f.env)).status,303
  );
  const before=f.vaultStorage.rows.size;
  const blocked=await worker.fetch(f.request("/oauth/github/start"),f.env);
  assert.equal(blocked.status,429);
  assert.equal(blocked.headers.get("retry-after"),"600");
  assert.equal(f.vaultStorage.rows.size,before);
  assert.equal(f.calls.length,0);
  const contents=JSON.stringify([...f.limitStorage.rows]);
  assert.equal(contents.includes("203.0.113.42"),false);
});

test("disabled staging and missing security bindings never make a network call",async()=>{
  const f=fixture();
  const cases=[
    {env:{...f.env,VIDEO_AUTH_ENABLED:"false"},status:503},
    {env:{...f.env,VIDEO_AUTH_DEPLOYMENT_APPROVED:"false"},status:503},
    {env:{...f.env,VIDEO_AUTH_RATE_GATE:null},status:503},
    {env:{...f.env,VIDEO_OAUTH_VAULT:null},status:503},
    {env:{...f.env,VIDEO_AUTH_RATE_KEY_HEX:"invalid"},status:400},
  ];
  await localGithub(async()=>{
    for(const item of cases){
      assert.equal((await worker.fetch(f.request("/oauth/github/start"),item.env)).status,item.status);
    }
    assert.equal(f.vaultStorage.rows.size,0);
    assert.equal(f.calls.length,0);
  },f.github);
});

test("staging alarm cleanup only deletes expired auth records and preserves owner data",async()=>{
  const f=fixture();
  const started=await worker.fetch(f.request("/oauth/github/start"),f.env);
  assert.equal(started.status,303);
  const now=Math.floor(Date.now()/1000);
  f.vaultStorage.rows.set("video:owner:active",{ownerId:77});
  const cleaned=await f.vault().cleanupExpired(now+601);
  assert.deepEqual(cleaned,{pending:1,session:0});
  assert.equal(f.vaultStorage.rows.size,1);
  assert.deepEqual(f.vaultStorage.rows.get("video:owner:active"),{ownerId:77});
});
