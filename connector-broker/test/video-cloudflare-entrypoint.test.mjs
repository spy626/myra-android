import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";

const entryPath=new URL("../src/video-auth-worker-cloudflare.mjs",import.meta.url);
const envEntry=readFileSync(entryPath,"utf8");
const manifest=readFileSync(new URL("../wrangler.video-auth.example.toml",import.meta.url),"utf8");

// Cloudflare's built-in import is unavailable to Node; substitute ONLY for
// this test so real wrapper methods run with the actual core adapters.
async function loadWrappersWithTestBase() {
  assert.match(envEntry,/import \{DurableObject\} from "cloudflare:workers"/);
  let src=envEntry.replace(
    'import {DurableObject} from "cloudflare:workers";',
    'class DurableObject { constructor(ctx,env) { this.ctx=ctx; this.env=env; } }'
  );
  for(const file of ["video-auth-worker.mjs","video-oauth-vault.mjs",
                      "video-owner-durable-object.mjs"]) {
    src=src.replace('from "./'+file+'"',
      'from "'+new URL("../src/"+file,import.meta.url).href+'"');
  }
  return import("data:text/javascript;base64,"+Buffer.from(src).toString("base64"));
}
class Storage {
  constructor(){this.db=new Map();}
  async get(k){return this.db.get(k);}
  async delete(k){this.db.delete(k);}
  async transaction(fn){
    const tx={get:async k=>this.db.get(k),
      put:async(k,v)=>this.db.set(k,structuredClone(v)),
      delete:async k=>this.db.delete(k)};
    return fn(tx);
  }
}

test("staging Wrangler selects only Cloudflare RPC-compatible entrypoint",()=>{
  assert.match(manifest,/main = "src\/video-auth-worker-cloudflare.mjs"/);
  assert.match(manifest,/VIDEO_AUTH_ENABLED = "false"/);
  assert.match(manifest,/VIDEO_AUTH_DEPLOYMENT_APPROVED = "false"/);
  assert.match(manifest,/new_sqlite_classes = \["VideoOAuthVault", "VideoRenderOwnerDurableObject"\]/);
  assert.match(envEntry,/class VideoOAuthVault extends DurableObject/);
  assert.match(envEntry,/class VideoRenderOwnerDurableObject extends DurableObject/);
});

test("Cloudflare RPC wrapper delegates to encrypted vault and owner quota adapters",async()=>{
  const {default:worker,VideoOAuthVault,VideoRenderOwnerDurableObject}=
    await loadWrappersWithTestBase();
  assert.equal(typeof worker.fetch,"function");
  const now=Math.floor(Date.now()/1000);
  const storage=new Storage();
  const vault=new VideoOAuthVault({storage},{VIDEO_SESSION_KEY_HEX:"f".repeat(64)});
  const sessionId="s_0123456789abcdef0123456789abcdef";
  const token="ghu_cloudflare_rpc_test_token_123456789";
  await vault.saveSession({sessionId,githubUserId:4321,accessToken:token,
    nowSeconds:now,expiresAt:now+600});
  assert.equal((await vault.load(sessionId)).githubUserId,4321);
  await vault.revoke(sessionId);
  assert.equal(await vault.load(sessionId),null);
  const ledger=new VideoRenderOwnerDurableObject({storage:new Storage()},{});
  const request={ownerId:4321,sessionId,
    nonce:"00000000-0000-4000-8000-000000000121",projectId:"mini_house",
    nowSeconds:now,expiresAt:now+300,maxActive:1,maxPerUtcDay:3};
  assert.equal(await ledger.reserveFromTrustedBroker(request),true);
  assert.equal(await ledger.reserveFromTrustedBroker(request),false);
  assert.equal((await ledger.readFromTrustedBroker(4321,now)).dailyUsed,1);
  await assert.rejects(ledger.readFromTrustedBroker(4322,now),/scope|another/);
});
