import {test} from "node:test";
import assert from "node:assert/strict";
import {VideoOAuthVault} from "../src/video-oauth-vault.mjs";

const NOW=1795000000;
const stateA="S".repeat(43),stateB="T".repeat(43);
const proof="a".repeat(64), verifier="V".repeat(43);
const idA="s_0123456789abcdef0123456789abcdef";
const idB="s_abcdef0123456789abcdef0123456789";
const token="ghu_staging_cleanup_test_oauth_token_0123456789";

class Storage {
  constructor(map=new Map()){this.data=map;this.alarmAt=null;this.tail=Promise.resolve();}
  async get(key){return structuredClone(this.data.get(key));}
  async delete(key){this.data.delete(key);}
  async list({prefix}){return new Map([...this.data].filter(([k])=>k.startsWith(prefix)).map(([k,v])=>[k,structuredClone(v)]));}
  async getAlarm(){return this.alarmAt;}
  async setAlarm(at){this.alarmAt=at;}
  async transaction(fn){
    const prev=this.tail;let release;
    this.tail=new Promise(r=>release=r);await prev;
    const next=new Map([...this.data].map(([k,v])=>[k,structuredClone(v)]));
    const tx={get:async k=>structuredClone(next.get(k)),put:async(k,v)=>next.set(k,structuredClone(v)),
      delete:async k=>next.delete(k)};
    try{const result=await fn(tx);this.data.clear();for(const [k,v] of next)this.data.set(k,v);return result;}
    finally{release();}
  }
}
const make=store=>new VideoOAuthVault({storage:store},{VIDEO_SESSION_KEY_HEX:"a".repeat(64)});
const begin=(v,state,expiresAt)=>v.begin({state,verifier,cookieHash:proof,nowSeconds:NOW,expiresAt});
const save=(v,sessionId,expiresAt)=>v.saveSession({
  sessionId,githubUserId:1234,accessToken:token,nowSeconds:NOW,expiresAt
});

test("alarm cleanup removes only expired pending login and encrypted session",async()=>{
  const db=new Storage(),v=make(db);
  await begin(v,stateA,NOW+5);
  await begin(v,stateB,NOW+300);
  await save(v,idA,NOW+10);
  await save(v,idB,NOW+600);
  assert.equal(db.alarmAt,(NOW+5)*1000);
  const dump=JSON.stringify([...db.data]);
  assert.ok(!dump.includes(token)&&!dump.includes(stateA)&&!dump.includes(idA));
  assert.deepEqual(await make(db).cleanupExpired(NOW+11),{pending:1,session:1});
  assert.equal(db.data.size,2);
  assert.equal(db.alarmAt,(NOW+300)*1000);
  assert.equal((await make(db).load(idB)).githubUserId,1234);
  assert.equal(await make(db).load(idA),null);
  assert.equal(await make(db).take({state:stateB,cookieHash:proof,nowSeconds:NOW+12}),verifier);
  assert.deepEqual(await make(db).cleanupExpired(NOW+700),{pending:0,session:1});
  assert.equal(db.data.size,0);
});

test("retry idempotence, no effect on owner reservations or rate limits",async()=>{
  const db=new Storage(),v=make(db);
  await begin(v,stateA,NOW+5);
  db.data.set("video:v1:active",{ownerId:55,state:"RESERVED_NOT_DISPATCHED"});
  db.data.set("video:v1:owner",55);
  db.data.set("video:oauth-rate:v1:global:start:1",3);
  assert.deepEqual(await v.cleanupExpired(NOW+6),{pending:1,session:0});
  assert.deepEqual(await v.cleanupExpired(NOW+6),{pending:0,session:0});
  assert.equal(db.data.size,3);
  assert.equal(db.data.get("video:v1:active").state,"RESERVED_NOT_DISPATCHED");
});

test("already-revoked session and consumed state cannot reappear after alarm",async()=>{
  const db=new Storage(),v=make(db);
  await begin(v,stateA,NOW+500);
  assert.equal(await v.take({state:stateA,cookieHash:proof,nowSeconds:NOW+2}),verifier);
  await save(v,idA,NOW+200);
  assert.equal(await v.revoke(idA),true);
  assert.deepEqual(await make(db).cleanupExpired(NOW+1000),{pending:0,session:0});
  assert.equal(db.data.size,0);
});

test("unavailable alarm refuses new OAuth state and encrypted session storage",async()=>{
  assert.throws(()=>make({transaction:()=>{}}),/alarms/);
  const db=new Storage(),v=make(db);
  db.setAlarm=async()=>{throw Error("alarm outage");};
  await assert.rejects(begin(v,stateA,NOW+100),/alarm outage/);
  await assert.rejects(save(v,idA,NOW+100),/alarm outage/);
  assert.equal(db.data.size,0);
});

test("malformed record fails closed rather than deleting arbitrary keys",async()=>{
  const db=new Storage(),v=make(db);
  db.data.set("oauth:pending:"+"b".repeat(64),{expiresAt:"fake"});
  db.data.set("video:v1:active",{state:"RESERVED_NOT_DISPATCHED"});
  await assert.rejects(v.cleanupExpired(NOW+1),/Malformed/);
  await assert.rejects(v.cleanupExpired(0),/time/);
  assert.equal(db.data.get("video:v1:active").state,"RESERVED_NOT_DISPATCHED");
});

test("expired state fails authentication even before alarm fires",async()=>{
  const db=new Storage(),v=make(db);
  await begin(v,stateA,NOW+5);
  await assert.rejects(v.take({state:stateA,cookieHash:proof,nowSeconds:NOW+6}),/expired/);
  assert.deepEqual(await v.cleanupExpired(NOW+7),{pending:0,session:0});
  await assert.rejects(v.take({state:stateA,cookieHash:proof,nowSeconds:NOW+8}),/binding/);
});
