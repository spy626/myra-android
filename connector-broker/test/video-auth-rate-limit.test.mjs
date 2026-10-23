import {test} from "node:test";
import assert from "node:assert/strict";
import {VideoOAuthRateGate} from "../src/video-auth-rate-limit.mjs";
import worker from "../src/video-auth-worker.mjs";

class FakeDurableStore {
  constructor(backing=new Map()){this.map=backing;this.tail=Promise.resolve();this.nextAlarm=null;}
  async getAlarm(){return this.nextAlarm;}
  async setAlarm(at){this.nextAlarm=at;}
  async list({prefix}){return new Map([...this.map].filter(([k])=>k.startsWith(prefix)));}
  async delete(key){this.map.delete(key);}
  async transaction(fn){
    const before=this.tail;let unlock;
    this.tail=new Promise(resolve=>{unlock=resolve;});await before;
    const updated=new Map([...this.map].map(([k,v])=>[k,structuredClone(v)]));
    const tx={get:async k=>updated.get(k),put:async(k,v)=>{updated.set(k,structuredClone(v));}};
    try{const out=await fn(tx);this.map.clear();for(const [k,v] of updated)this.map.set(k,v);return out;}
    finally{unlock();}
  }
}
const NOW=1795000000;
const source=n=>n.toString(16).padStart(64,"0");
const item=(route,key=source(1),nowSeconds=NOW)=>({route,sourceKey:key,nowSeconds});

test("client and global budgets hold across restart and parallel races",async()=>{
  const db=new FakeDurableStore();const gate=new VideoOAuthRateGate({storage:db});
  const result=await Promise.all(Array.from({length:25},()=>gate.reserveAttempt(item("start"))));
  assert.equal(result.filter(Boolean).length,4);
  assert.equal(await new VideoOAuthRateGate({storage:db}).reserveAttempt(item("start")),false);
  // Source rotation cannot bypass a singleton global cap of thirty starts.
  for(let n=2;n<=26;n++)assert.equal(await gate.reserveAttempt(item("start",source(n))),true);
  assert.equal(await gate.reserveAttempt(item("start",source(27))),true);
  assert.equal(await gate.reserveAttempt(item("start",source(28))),false);
  assert.ok(db.nextAlarm>NOW*1000);
});

test("callback has a separate tighter scope and cannot bypass its own caps",async()=>{
  const gate=new VideoOAuthRateGate({storage:new FakeDurableStore()});
  for(let i=0;i<8;i++)assert.equal(await gate.reserveAttempt(item("callback")),true);
  assert.equal(await gate.reserveAttempt(item("callback")),false);
  assert.equal(await gate.reserveAttempt(item("start")),true);
});

test("expired windows clean up through alarm without resetting a current quota",async()=>{
  const db=new FakeDurableStore(),gate=new VideoOAuthRateGate({storage:db});
  assert.equal(await gate.reserveAttempt(item("start")),true);
  const later=NOW+600;
  assert.equal(await gate.reserveAttempt(item("start",source(1),later)),true);
  const before=db.map.size;
  assert.equal(await gate.cleanupExpired(NOW+1800),before);
  assert.equal(db.map.size,0);
  assert.equal(await gate.reserveAttempt(item("start",source(1),NOW+1800)),true);
});

test("invalid fields, corrupted counters and missing durable alarms fail closed",async()=>{
  const db=new FakeDurableStore(),gate=new VideoOAuthRateGate({storage:db});
  for(const request of [
    item("delete"),item("start","fake"),item("start",source(1),1),
    item("start",source(1),Number.NaN),
  ])await assert.rejects(gate.reserveAttempt(request));
  const bucket=Math.floor(NOW/600);
  db.map.set("video:oauth-rate:v1:global:start:"+bucket,"corrupt");
  await assert.rejects(gate.reserveAttempt(item("start")),/counter/);
  assert.throws(()=>new VideoOAuthRateGate({storage:{transaction:()=>{}}}),/storage/);
});

test("Worker rejects missing limiter and invalid source, no raw IP is stored",async()=>{
  const store=new FakeDurableStore(),gate=new VideoOAuthRateGate({storage:store});
  let began=0;
  const env={
    VIDEO_AUTH_ENABLED:"true",VIDEO_AUTH_DEPLOYMENT_APPROVED:"true",
    VIDEO_AUTH_PUBLIC_ORIGIN:"https://auth-test.example.org",
    VIDEO_GITHUB_CALLBACK_URL:"https://auth-test.example.org/oauth/github/callback",
    VIDEO_GITHUB_CLIENT_ID:"Ov23li_test_client_00000",
    VIDEO_GITHUB_CLIENT_SECRET:"test_secret_example_0123456789",
    VIDEO_AUTH_RATE_KEY_HEX:"b".repeat(64),
    VIDEO_OAUTH_VAULT:{idFromName:n=>n,get:()=>({begin:async()=>{began++;}})},
    VIDEO_AUTH_RATE_GATE:{idFromName:n=>n,get:()=>gate},
  };
  const req=(ip,opts={})=>{
    const h={};if(ip!==null)h["CF-Connecting-IP"]=ip;
    return new Request("https://auth-test.example.org/oauth/github/start",{headers:h,...opts});
  };
  assert.equal((await worker.fetch(req("203.0.113.99"),{...env,VIDEO_AUTH_RATE_GATE:null})).status,503);
  assert.equal((await worker.fetch(req(null),env)).status,400);
  assert.equal((await worker.fetch(req("999.0.0.1"),env)).status,400);
  assert.equal(began,0);
  for(let i=0;i<4;i++) assert.equal((await worker.fetch(req("203.0.113.99"),env)).status,303);
  const denied=await worker.fetch(req("203.0.113.99"),env);
  assert.equal(denied.status,429);
  assert.equal(denied.headers.get("retry-after"),"600");
  assert.equal(began,4);
  const data=JSON.stringify([...store.map]);
  assert.equal(data.includes("203.0.113.99"),false);
  assert.equal(data.includes("test_secret_example"),false);
});

test("fail closed when scheduling cleanup fails, without deleting counters",async()=>{
  const store=new FakeDurableStore(),gate=new VideoOAuthRateGate({storage:store});
  store.setAlarm=async()=>{throw Error("alarm outage");};
  await assert.rejects(gate.reserveAttempt(item("start"))/****/,/alarm outage/);
  assert.equal(store.map.size,2);
});
