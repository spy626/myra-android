import {test} from "node:test";
import assert from "node:assert/strict";
import {beginBrowserGitHubOAuth,finishBrowserGitHubOAuth} from "../src/video-oauth-flow.mjs";
import {VideoOAuthVault} from "../src/video-oauth-vault.mjs";

class Store {
  constructor(){this.data=new Map();this.tail=Promise.resolve();}
  get(k){return Promise.resolve(structuredClone(this.data.get(k)));}
  async getAlarm(){return this.alarmAt??null;}
  async setAlarm(at){this.alarmAt=at;}
  async list({prefix}){return new Map([...this.data].filter(([key])=>key.startsWith(prefix)));}

  delete(k){this.data.delete(k);return Promise.resolve();}
  async transaction(fn){
    let release;const previous=this.tail;this.tail=new Promise(r=>release=r);await previous;
    const draft=new Map([...this.data].map(([k,v])=>[k,structuredClone(v)]));
    const tx={get:async k=>draft.get(k),put:async(k,v)=>draft.set(k,structuredClone(v)),
      delete:async k=>draft.delete(k)};
    try{const value=await fn(tx);this.data=draft;return value;}finally{release();}
  }
}
const now=Math.floor(Date.now()/1000);
const env={
  VIDEO_GITHUB_CLIENT_ID:"Ov23li_valid_test_client",
  VIDEO_GITHUB_CLIENT_SECRET:"github_test_secret_xxxxxx_0123456789",
  VIDEO_GITHUB_CALLBACK_URL:"https://auth-test.example.org/oauth/github/callback",
  VIDEO_SESSION_KEY_HEX:"c".repeat(64),
};
const token="ghu_fake_user_token_read_only_0123456789";
const json=data=>new Response(JSON.stringify(data),{status:200,
  headers:{"content-type":"application/json"}});

test("GitHub OAuth start uses only PKCE S256, read:user and fixed HTTPS callback",async()=>{
  const v=new VideoOAuthVault({storage:new Store()},env);
  const {authorizationUrl,browserProof}=await beginBrowserGitHubOAuth({env,vault:v,nowSeconds:now});
  const url=new URL(authorizationUrl);
  assert.equal(url.origin,"https://github.com");
  assert.equal(url.pathname,"/login/oauth/authorize");
  assert.equal(url.searchParams.get("scope"),"read:user");
  assert.equal(url.searchParams.get("code_challenge_method"),"S256");
  assert.equal(url.searchParams.get("redirect_uri"),env.VIDEO_GITHUB_CALLBACK_URL);
  assert.match(url.searchParams.get("state"),/^[A-Za-z0-9_-]{32,128}$/);
  assert.match(url.searchParams.get("code_challenge"),/^[A-Za-z0-9_-]{43}$/);
  assert.match(browserProof,/^[A-Za-z0-9_-]{43}$/);
  assert.equal(url.searchParams.has("client_secret"),false);
});

test("one-time GitHub callback exchanges PKCE code, verifies real user and stores no browser token",async()=>{
  const db=new Store(),v=new VideoOAuthVault({storage:db},env);
  const result=await beginBrowserGitHubOAuth({env,vault:v,nowSeconds:now});
  const state=new URL(result.authorizationUrl).searchParams.get("state");
  const challenge=new URL(result.authorizationUrl).searchParams.get("code_challenge");
  let requests=0;
  const fetchImpl=async(url,opts)=>{
    requests++;
    assert.equal(opts.redirect,"manual");
    if(url==="https://github.com/login/oauth/access_token"){
      assert.equal(opts.method,"POST");
      const body=JSON.parse(opts.body);
      assert.equal(body.redirect_uri,env.VIDEO_GITHUB_CALLBACK_URL);
      assert.equal(body.client_secret,env.VIDEO_GITHUB_CLIENT_SECRET);
      const sha=new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(body.code_verifier)));
      const derived=btoa(String.fromCharCode(...sha)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/g,"");
      assert.equal(derived,challenge);
      return json({access_token:token,token_type:"bearer",scope:"read:user",expires_in:1200});
    }
    assert.equal(url,"https://api.github.com/user");
    assert.equal(opts.headers.Authorization,"Bearer "+token);
    return json({id:39213,login:"verified-user"});
  };
  await assert.rejects(finishBrowserGitHubOAuth({env,vault:v,nowSeconds:now,
    state,code:"sample_code_0123456789",browserProof:"f".repeat(43),
    fetchImpl}),/binding/);
  assert.equal(requests,0);
  const completed=await finishBrowserGitHubOAuth({env,vault:v,nowSeconds:now,
    state,code:"sample_code_0123456789",browserProof:result.browserProof,
    fetchImpl});
  assert.equal(requests,2);
  assert.equal(completed.maxAgeSeconds,1200);
  assert.deepEqual(Object.keys(completed).sort(),["maxAgeSeconds","sessionId"]);
  const vaultRecord=await v.load(completed.sessionId);
  assert.equal(vaultRecord.githubUserId,39213);
  assert.equal(vaultRecord.accessToken,token);
  assert.ok(!JSON.stringify(completed).includes(token));
  await assert.rejects(finishBrowserGitHubOAuth({env,vault:v,nowSeconds:now,
    state,code:"sample_code_0123456789",browserProof:result.browserProof,fetchImpl}),/binding/);
  assert.equal(requests,2);
});

test("expired state, GitHub identity failure, redirects and untrusted callback fail closed",async()=>{
  for(const failure of [
    async()=>json({error:"bad_verification_code"}),
    async()=>new Response(null,{status:302,headers:{Location:"https://evil.example"}}),
    async()=>json({access_token:token,token_type:"mac"}),
    async()=>json({access_token:token,token_type:"bearer",expires_in:0}),
    async()=>json({access_token:token,token_type:"bearer",scope:"repo,read:user"}),
    async()=>json({access_token:token,token_type:"bearer"}),
    async()=>json({access_token:token,token_type:"bearer",scope:""}),
    async()=>json({access_token:token,token_type:"bearer",scope:"read:user,"}),
  ]){
    const v=new VideoOAuthVault({storage:new Store()},env);
    const start=await beginBrowserGitHubOAuth({env,vault:v,nowSeconds:now});
    const state=new URL(start.authorizationUrl).searchParams.get("state");
    await assert.rejects(finishBrowserGitHubOAuth({env,vault:v,nowSeconds:now,
      state,code:"sample_code_0123456789",browserProof:start.browserProof,
      fetchImpl:failure}));
  }
  const v=new VideoOAuthVault({storage:new Store()},env);
  const start=await beginBrowserGitHubOAuth({env,vault:v,nowSeconds:now});
  const state=new URL(start.authorizationUrl).searchParams.get("state");
  await assert.rejects(finishBrowserGitHubOAuth({env,vault:v,nowSeconds:now+601,
    state,code:"sample_code_0123456789",browserProof:start.browserProof,
    fetchImpl:async()=>{throw Error("network must not happen");}}),/expired/);
  await assert.rejects(beginBrowserGitHubOAuth({env:{...env,VIDEO_GITHUB_CALLBACK_URL:"http://localhost/oauth/github/callback"},
    vault:v,nowSeconds:now}));
});

test("remote user verification rejects malformed/oversize/unauthorized replies",async()=>{
  for(const failure of [
    json({id:"39213",login:"wrong"}),
    json({id:39213,login:"bad login"}),
    new Response(JSON.stringify({id:39213,login:"verified-user"}),{status:401,
      headers:{"content-type":"application/json"}}),
    new Response("x".repeat(17000),{status:200,
      headers:{"content-type":"application/json"}}),
  ]){
    const v=new VideoOAuthVault({storage:new Store()},env);
    const start=await beginBrowserGitHubOAuth({env,vault:v,nowSeconds:now});
    await assert.rejects(finishBrowserGitHubOAuth({env,vault:v,nowSeconds:now,
      state:new URL(start.authorizationUrl).searchParams.get("state"),
      code:"sample_code_0123456789",browserProof:start.browserProof,
      fetchImpl:async url=>url.includes("access_token")?json({access_token:token,scope:"read:user"}):failure}));
  }
});
