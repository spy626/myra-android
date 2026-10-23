import {test} from "node:test";
import assert from "node:assert/strict";
import {VideoOAuthVault} from "../src/video-oauth-vault.mjs";
import {verifyGithubOwnerFromServerSession} from "../src/video-github-session.mjs";

class DurableMemoryTestDouble {
  constructor(backing=new Map()){this.db=backing;this.tail=Promise.resolve();}
  get(key){return Promise.resolve(structuredClone(this.db.get(key)));}
  async delete(key){this.db.delete(key);}
  async transaction(callback){
    let release;const prior=this.tail;this.tail=new Promise(resolve=>release=resolve);await prior;
    const db=new Map([...this.db].map(([k,v])=>[k,structuredClone(v)]));
    const tx={get:async k=>structuredClone(db.get(k)),
      put:async(k,v)=>{db.set(k,structuredClone(v));},
      delete:async k=>{db.delete(k);}};
    try{const val=await callback(tx);this.db.clear();for(const [k,v] of db)this.db.set(k,v);return val;}
    finally{release();}
  }
}
const now=Math.floor(Date.now()/1000);
const secret="a".repeat(64);
const state="S".repeat(43), proof="a".repeat(64), verifier="V".repeat(43);
const id="s_0123456789abcdef0123456789abcdef";
const token="ghu_example_oauth_token_for_node_test_12345678";
const vault=(store,env={VIDEO_SESSION_KEY_HEX:secret})=>
  new VideoOAuthVault({storage:store},env);

test("one-time browser state is durable; wrong browser cannot consume it",async()=>{
  const db=new DurableMemoryTestDouble();const first=vault(db);
  await first.begin({state,verifier,cookieHash:proof,nowSeconds:now,expiresAt:now+600});
  await assert.rejects(vault(db).take({state,cookieHash:"b".repeat(64),nowSeconds:now+3}),/binding/);
  assert.equal(await vault(db).take({state,cookieHash:proof,nowSeconds:now+3}),verifier);
  await assert.rejects(first.take({state,cookieHash:proof,nowSeconds:now+4}),/binding/);
});

test("expired approval state fails before issuing a server session",async()=>{
  const db=new DurableMemoryTestDouble();const v=vault(db);
  await v.begin({state,verifier,cookieHash:proof,nowSeconds:now,expiresAt:now+5});
  await assert.rejects(v.take({state,cookieHash:proof,nowSeconds:now+6}),/expired/);
  await assert.rejects(v.take({state,cookieHash:proof,nowSeconds:now+4}),/binding/);
});

test("opaque sessions persist encrypted at rest; same server key restores after restart",async()=>{
  const db=new DurableMemoryTestDouble();
  await vault(db).saveSession({sessionId:id,githubUserId:39213,accessToken:token,
    nowSeconds:now,expiresAt:now+900});
  const dump=JSON.stringify([...db.db]);
  assert.ok(!dump.includes(token));
  assert.ok(!dump.includes(id));
  assert.ok(!dump.includes("Bearer"));
  const session=await vault(db).load(id);
  assert.deepEqual(session,{kind:"server-issued-github-oauth",id,
    githubUserId:39213,accessToken:token,expiresAt:now+900});
  assert.equal(await vault(db).load("not-a-session"),null);
  await assert.rejects(vault(db,{VIDEO_SESSION_KEY_HEX:"b".repeat(64)}).load(id));
  await assert.rejects(vault(db).saveSession({sessionId:id,githubUserId:39213,
    accessToken:token,nowSeconds:now,expiresAt:now+900}),/collision/);
  assert.equal(await vault(db).revoke(id),true);
  assert.equal(await vault(db).load(id),null);
});

test("staging vault preserves Slice 10 real-server GitHub owner validation",async()=>{
  const db=new DurableMemoryTestDouble();
  await vault(db).saveSession({sessionId:id,githubUserId:39213,accessToken:token,
    nowSeconds:now,expiresAt:now+800});
  const principal=await verifyGithubOwnerFromServerSession({
    sessionId:id,vault:vault(db),nowSeconds:now,
    fetchImpl:async (url,params)=>{
      assert.equal(url,"https://api.github.com/user");
      assert.equal(params.headers.Authorization,"Bearer "+token);
      return Response.json({id:39213,login:"verified-user"});
    },
  });
  assert.deepEqual(principal,{kind:"verified-github-oauth",githubUserId:39213,sessionId:id});
  assert.ok(!JSON.stringify(principal).includes(token));
  await assert.rejects(verifyGithubOwnerFromServerSession({
    sessionId:id,vault:vault(db),nowSeconds:now,
    fetchImpl:async()=>Response.json({id:777,login:"attacker"}),
  }),/mismatch|match/);
});

test("reject malformed state, session, secret and unsafe lifetime",async()=>{
  const db=new DurableMemoryTestDouble();const v=vault(db);
  await assert.rejects(v.begin({state:"1",verifier,cookieHash:proof,nowSeconds:now,expiresAt:now+600}));
  await assert.rejects(v.begin({state,verifier,cookieHash:proof,nowSeconds:now,expiresAt:now+601}));
  await assert.rejects(v.saveSession({sessionId:id,githubUserId:39213,accessToken:token,
    nowSeconds:now,expiresAt:now+1801}));
  await assert.rejects(vault(db,{VIDEO_SESSION_KEY_HEX:"bad"}).saveSession({
    sessionId:id,githubUserId:39213,accessToken:token,nowSeconds:now,expiresAt:now+600,
  }));
});
