import {test} from 'node:test';
import assert from 'node:assert/strict';
import {verifyGithubOwnerFromServerSession} from '../src/video-github-session.mjs';
const handle='s_0123456789abcdef0123456789abcdef';
const now=1_795_000_000;
const record={kind:'server-issued-github-oauth', id:handle,
  githubUserId:4145, accessToken:'github_oauth_token_valid_test_12345',
  expiresAt:now+3600};
const vault={async load(x){return x===handle?structuredClone(record):null;}};
const jsonResponse=(body,status=200,contentType='application/json')=>
  new Response(JSON.stringify(body),{status,headers:{'content-type':contentType}});
const fetchValid=async (url,opts)=>{
  assert.equal(url,'https://api.github.com/user');
  assert.equal(opts.method,'GET');
  assert.equal(opts.redirect,'manual');
  assert.equal(opts.headers.Authorization, 'Bearer '+record.accessToken);
  return jsonResponse({id:4145,login:'verified-user'});
};
const resolve=(overrides={})=>verifyGithubOwnerFromServerSession({
  sessionId:handle,vault,fetchImpl:fetchValid,nowSeconds:now,...overrides,
});

test('verifies server-vault OAuth owner through GitHub /user and returns no token',async()=>{
  const principal=await resolve();
  assert.deepEqual(principal,{kind:'verified-github-oauth',githubUserId:4145,sessionId:handle});
  assert.equal(JSON.stringify(principal).includes('accessToken'),false);
});

test('fails closed on fake or expired server session',async()=>{
  for(const props of [{sessionId:'forged'}, {nowSeconds:now+3601},
    {vault:{async load(){return {...record,githubUserId:555};}}},
    {vault:{async load(){return {...record,kind:'phone-asserted'};}}},
    {vault:{async load(){return null;}}},
    {vault:{async load(){return {...record,accessToken:'bad'};}}},
  ])await assert.rejects(resolve(props));
});

test('GitHub OAuth identity mismatch or remote auth error cannot mint principal',async()=>{
  for(const mock of [
    async()=>jsonResponse({id:4146,login:'another-owner'}),
    async()=>jsonResponse({id:4145,login:'wrong login!'}),
    async()=>jsonResponse({id:4145,login:'verified-user'},401),
    async()=>jsonResponse({id:4145,login:'verified-user'},429),
    async()=>jsonResponse({id:4145,login:'verified-user'},200,'text/plain'),
    async()=>new Response('x'.repeat(17000),{status:200,headers:{'content-type':'application/json'}}),
  ]) await assert.rejects(resolve({fetchImpl:mock}));
});

test('cannot be used with missing server vault or arbitrary phone tokens',async()=>{
  await assert.rejects(resolve({vault:null}));
  await assert.rejects(resolve({fetchImpl:null}));
  await assert.rejects(resolve({sessionId:record.accessToken}));
});