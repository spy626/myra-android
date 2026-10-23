import {test} from "node:test";
import assert from "node:assert/strict";
import worker from "../src/video-auth-worker.mjs";
import {VideoOAuthVault} from "../src/video-oauth-vault.mjs";

class FakeDurableStorage {
  constructor(){this.data=new Map();this.tail=Promise.resolve();}
  async get(key){return structuredClone(this.data.get(key));}
  async getAlarm(){return this.alarmAt??null;}
  async setAlarm(at){this.alarmAt=at;}
  async list({prefix}){return new Map([...this.data].filter(([key])=>key.startsWith(prefix)));}

  async delete(key){this.data.delete(key);}
  async transaction(fn){
    const before=this.tail;let unlock;
    this.tail=new Promise(resolve=>{unlock=resolve;});
    await before;
    const next=new Map([...this.data].map(([k,v])=>[k,structuredClone(v)]));
    const tx={get:async k=>structuredClone(next.get(k)),
      put:async(k,v)=>{next.set(k,structuredClone(v));},
      delete:async k=>{next.delete(k);}};
    try {const result=await fn(tx);this.data=next;return result;}
    finally {unlock();}
  }
}
const origin="https://video-auth-test.example.org";
const sessionId="s_0123456789abcdef0123456789abcdef";
const token="ghu_logout_test_session_token_01234567890";
const key="a".repeat(64);
const name="__Host-lyra_video_session";
function fixture(){
  const storage=new FakeDurableStorage();
  const adapter=new VideoOAuthVault({storage},{VIDEO_SESSION_KEY_HEX:key});
  const env={
    VIDEO_AUTH_ENABLED:"true",VIDEO_AUTH_DEPLOYMENT_APPROVED:"true",
    VIDEO_AUTH_PUBLIC_ORIGIN:origin,
    VIDEO_GITHUB_CALLBACK_URL:origin+"/oauth/github/callback",
    VIDEO_OAUTH_VAULT:{idFromName:x=>x,get:()=>adapter},
  };
  const now=Math.floor(Date.now()/1000);
  const createSession=()=>adapter.saveSession({
    sessionId,githubUserId:4282,accessToken:token,
    nowSeconds:now,expiresAt:now+600,
  });
  const request=(path="/oauth/github/logout",{
    method="POST",originHeader=origin,site="same-origin",
    cookies=name+"="+sessionId,body,
  }={})=>{
    const headers=new Headers();
    if(originHeader!==null)headers.set("Origin",originHeader);
    if(site!==null)headers.set("Sec-Fetch-Site",site);
    if(cookies!==null)headers.set("Cookie",cookies);
    return new Request(origin+path,{method,headers,...(body===undefined?{}:{body})});
  };
  return {storage,adapter,env,request,createSession};
}

test("same-origin POST logout revokes durable session before clearing both cookies",async()=>{
  const f=fixture();await f.createSession();
  assert.equal((await f.adapter.load(sessionId)).githubUserId,4282);
  const result=await worker.fetch(f.request(),f.env);
  assert.equal(result.status,200);
  assert.deepEqual(await result.json(),{signed_out:true,render_submitted:false});
  const set=result.headers.get("set-cookie")||"";
  assert.match(set,/__Host-lyra_video_session=; Path=\/; Secure; HttpOnly; SameSite=Lax; Max-Age=0/);
  assert.match(set,/__Host-lyra_video_pending=; Path=\/; Secure; HttpOnly; SameSite=Lax; Max-Age=0/);
  assert.equal(await f.adapter.load(sessionId),null);
  assert.equal(result.headers.get("cache-control"),"no-store");
  assert.equal(set.includes(token),false);
  assert.equal((await worker.fetch(f.request(),f.env)).status,200);
});

test("missing or mismatched origin and cross-site POST cannot revoke session",async()=>{
  const f=fixture();await f.createSession();
  for(const request of [
    f.request(undefined,{originHeader:null}),
    f.request(undefined,{originHeader:"https://attacker.example.org"}),
    f.request(undefined,{site:"cross-site"}),
    f.request(undefined,{site:"same-site"}),
    f.request(undefined,{body:"data"}),
  ]){
    const result=await worker.fetch(request,f.env);
    assert.equal(result.status,403);
    assert.equal(result.headers.get("set-cookie"),null);
    assert.notEqual(await f.adapter.load(sessionId),null);
  }
});

test("duplicate, missing and malformed session cookies fail closed",async()=>{
  const f=fixture();await f.createSession();
  for(const cookies of [
    null,name+"="+sessionId+"; "+name+"="+sessionId,
    name+"=not_a_valid_session",name+"="+sessionId+"; "+name+"=other",
  ]){
    const result=await worker.fetch(f.request(undefined,{cookies}),f.env);
    assert.equal(result.status,400);
    assert.equal(result.headers.get("set-cookie"),null);
    assert.notEqual(await f.adapter.load(sessionId),null);
  }
});

test("disabled staging, unsafe routes, query injection and GET logout are denied",async()=>{
  const f=fixture();await f.createSession();
  assert.equal((await worker.fetch(f.request(undefined,{method:"GET"}),f.env)).status,404);
  assert.equal((await worker.fetch(f.request("/oauth/github/logout?next=https://evil.example"),f.env)).status,403);
  assert.equal((await worker.fetch(f.request("/github/dispatch"),f.env)).status,403);
  assert.equal((await worker.fetch(f.request(),{...f.env,VIDEO_AUTH_ENABLED:"false"})).status,503);
  assert.notEqual(await f.adapter.load(sessionId),null);
});

test("durable revoke failure must not report logout success or clear cookies",async()=>{
  const f=fixture();await f.createSession();
  f.storage.delete=async()=>{throw Error("durable-storage-unavailable");};
  const result=await worker.fetch(f.request(),f.env);
  assert.equal(result.status,400);
  assert.deepEqual(await result.json(),{error:"oauth_request_rejected"});
  assert.equal(result.headers.get("set-cookie"),null);
  assert.notEqual(await f.adapter.load(sessionId),null);
});
