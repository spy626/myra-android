import {test} from "node:test";
import assert from "node:assert/strict";
import worker from "../src/video-auth-worker.mjs";
import {VideoOAuthVault} from "../src/video-oauth-vault.mjs";
import {VideoRenderOwnerDurableObject} from "../src/video-owner-durable-object.mjs";

class DurableDouble{
  constructor(){this.map=new Map();this.tail=Promise.resolve();}
  get(key){return Promise.resolve(structuredClone(this.map.get(key)));}
  delete(key){this.map.delete(key);return Promise.resolve();}
  async transaction(fn){
    let release;const prev=this.tail;this.tail=new Promise(r=>release=r);await prev;
    const copy=new Map([...this.map].map(([k,v])=>[k,structuredClone(v)]));
    const tx={get:async k=>copy.get(k),put:async(k,v)=>copy.set(k,structuredClone(v)),
      delete:async k=>copy.delete(k)};
    try{const x=await fn(tx);this.map=copy;return x;}finally{release();}
  }
}
const db=new DurableDouble();
const environment={
  VIDEO_AUTH_ENABLED:"true",
  VIDEO_AUTH_DEPLOYMENT_APPROVED:"true",
  VIDEO_AUTH_PUBLIC_ORIGIN:"https://auth-test.example.org",
  VIDEO_GITHUB_CALLBACK_URL:"https://auth-test.example.org/oauth/github/callback",
  VIDEO_GITHUB_CLIENT_ID:"Ov23li_test_client_00000",
  VIDEO_GITHUB_CLIENT_SECRET:"example_test_client_secret_xxxxxxxxx",
  VIDEO_SESSION_KEY_HEX:"e".repeat(64),
  VIDEO_AUTH_RATE_KEY_HEX:"b".repeat(64),
  VIDEO_AUTH_RATE_GATE:{idFromName:name=>name,get:()=>({reserveAttempt:async()=>true})},
  VIDEO_OAUTH_VAULT:{
    idFromName:name=>name,
    get:()=>new VideoOAuthVault({storage:db},{VIDEO_SESSION_KEY_HEX:"e".repeat(64)}),
  },
};
const req=(path,method="GET",headers={})=> {
  const h=new Headers(headers);
  if(!h.has("CF-Connecting-IP"))h.set("CF-Connecting-IP","203.0.113.42");
  return new Request("https://auth-test.example.org"+path,{method,headers:h});
};

test("staging worker refuses OAuth routes without separate approval and config",async()=>{
  const res=await worker.fetch(req("/oauth/github/start"),{
    ...environment,VIDEO_AUTH_DEPLOYMENT_APPROVED:"false",
  });
  assert.equal(res.status,503);
  assert.equal((await res.json()).error,"video_auth_not_deployed");
  assert.equal((await worker.fetch(req("/oauth/github/start"),{})).status,503);
});

test("only fixed GET OAuth endpoints; no dispatch, owner RPC or token route",async()=>{
  assert.equal((await worker.fetch(req("/github/dispatch"),environment)).status,404);
  assert.equal((await worker.fetch(req("/oauth/github/start","POST"),environment)).status,403);
  assert.equal((await worker.fetch(req("/oauth/github/start?return_to=https://evil.test"),environment)).status,404);
  assert.equal((await worker.fetch(
    new Request("https://other.example.org/oauth/github/start"),environment
  )).status,403);
  assert.equal((await worker.fetch(req("/oauth/github/callback?code=foo&state=bar"),environment)).status,400);
});

test("approved staging start is browser-bound, secure-cookie only, PKCE auth redirect",async()=>{
  const res=await worker.fetch(req("/oauth/github/start"),environment);
  assert.equal(res.status,303);
  assert.match(res.headers.get("location"),/^https:\/\/github\.com\/login\/oauth\/authorize\?/);
  assert.match(res.headers.get("set-cookie"),/__Host-lyra_video_pending=.*Secure; HttpOnly; SameSite=Lax/);
  assert.equal(res.headers.get("cache-control"),"no-store");
  assert.equal(res.headers.get("referrer-policy"),"no-referrer");
  assert.equal(res.headers.get("location").includes("client_secret"),false);
});

test("Durable Owner object has no public HTTP route and keeps pinned owner",async()=>{
  const memory=new DurableDouble();
  const object=new VideoRenderOwnerDurableObject({storage:memory},{});
  const req={ownerId:39213,sessionId:"s_0123456789abcdef0123456789abcdef",
    nonce:"00000000-0000-4000-8000-000000000001",projectId:"house_1",
    nowSeconds:1795000000,expiresAt:1795000300,maxActive:1,maxPerUtcDay:3};
  assert.equal(await object.reserveFromTrustedBroker(req),true);
  assert.equal(await object.reserveFromTrustedBroker(req),false);
  assert.equal((await object.readFromTrustedBroker(39213,1795000000)).dailyUsed,1);
  await assert.rejects(object.reserveFromTrustedBroker({
    ...req,ownerId:1,nonce:"00000000-0000-4000-8000-000000000002",
  }),/scope/);
});
