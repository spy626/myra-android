import {test} from "node:test";
import assert from "node:assert/strict";
import {readonlyWanVideoPreflight} from "../src/video-openrouter-preflight.mjs";

const KEY="sk-or-v1-test-secret-never-publish";
const TWO=["alibaba/wan-2.7:free","alibaba/wan-2.6:free"];
const model=(id,prices={generate:"0"})=>({
  id,pricing_skus:prices,
  supported_durations:[5,8],
  supported_aspect_ratios:["9:16","16:9"],
  supported_resolutions:["720p","480p"],
});
const catalogue=(entries=TWO.map(x=>model(x)))=>({data:entries});
const keyBody={data:{is_free_tier:true,is_management_key:false,
  label:"PRIVATE_API_KEY_NAME",creator_user_id:"PRIVATE_USER",usage:42.55}};
function makeMock({
  key=keyBody,
  models=catalogue(),
  keyStatus=200,catalogueStatus=200,
  keyBodyRaw=null,catalogueBodyRaw=null,
}={}){
  const calls=[];
  const fetchImpl=async(url,options)=>{
    calls.push({url,options});
    if(url==="https://openrouter.ai/api/v1/key"){
      return keyBodyRaw!==null
        ? new Response(keyBodyRaw,{status:keyStatus})
        : Response.json(key,{status:keyStatus});
    }
    if(url==="https://openrouter.ai/api/v1/videos/models"){
      return catalogueBodyRaw!==null
        ? new Response(catalogueBodyRaw,{status:catalogueStatus})
        : Response.json(models,{status:catalogueStatus});
    }
    throw Error("Unexpected network request: "+url);
  };
  return {fetchImpl,calls};
}
function assertAllRequestsAreReadOnly(calls){
  assert.ok(calls.length<=2);
  for(const call of calls){
    assert.equal(call.options.method,"GET");
    assert.equal(call.options.redirect,"manual");
    assert.equal(call.options.body,undefined);
    assert.ok(["https://openrouter.ai/api/v1/key",
      "https://openrouter.ai/api/v1/videos/models"].includes(call.url));
    assert.equal(call.options.headers.Authorization,"Bearer "+KEY);
  }
}
function privateDataNotInReport(r){
  const serialized=JSON.stringify(r);
  for(const forbidden of [KEY,"PRIVATE_API_KEY_NAME","PRIVATE_USER",
    "PRIVATE_TOKEN","Bearer ","https://attacker.example/", "42.55"]){
    assert.ok(!serialized.includes(forbidden),forbidden+" leaked in report");
  }
}
test("without server-held credential it is OFFLINE ONLY; makes zero requests",async()=>{
  const {fetchImpl,calls}=makeMock();
  for (const apiKey of [undefined,null,"","x",KEY+"\n"]){
    const r=await readonlyWanVideoPreflight({apiKey,fetchImpl});
    assert.equal(r.status,"SERVER_KEY_NOT_CONFIGURED");
    assert.equal(r.authenticated,false);
    assert.equal(r.video_submission_allowed,false);
    assert.equal(r.videos_generated,0);
    assert.equal(r.paid_requests_made,0);
    assert.equal(r.models.length,2);
    assert.equal(r.models[0].status,"NOT_CHECKED");
    privateDataNotInReport(r);
  }
  assert.equal(calls.length,0);
});
test("read-only authenticated preflight returns only exact free eligible models",async()=>{
  const {fetchImpl,calls}=makeMock();
  const r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl});
  assert.equal(r.status,"AUTHENTICATED_CATALOGUE_CHECKED");
  assert.equal(r.authenticated,true);
  assert.equal(r.free_tier_key,true);
  assert.deepEqual(r.models.map(x=>x.model),TWO);
  assert.ok(r.models.every(x=>
    x.status==="CATALOGUE_ZERO_PRICE_AND_SUPPORTED" &&
    x.resolution==="480p"));
  assert.equal(r.video_submission_allowed,false);
  assert.equal(r.videos_generated,0);
  assert.equal(r.paid_requests_made,0);
  assert.equal(calls.length,2);
  assertAllRequestsAreReadOnly(calls);
  privateDataNotInReport(r);
});
test("free page listing is not enough when authenticated catalogue missing",async()=>{
  const {fetchImpl,calls}=makeMock({models:catalogue([
    model("alibaba/wan-2.7",{generate:"0.10"}),
    model("google/veo-3.1",{generate:"0.50"}),
  ])});
  const r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl});
  assert.equal(r.models[0].status,"NOT_LISTED");
  assert.equal(r.models[1].status,"NOT_LISTED");
  assertAllRequestsAreReadOnly(calls);
});
test("paid or unknown pricing must never be presented as free",async()=>{
  for(const prices of [
    {generate:"0.05"}, {generate:0.00001}, {generate:null},
    {}, {generate:"0",generate_audio:"0.02"}, {generate:0,resize:"?"},
    {generate:"0.0000000000000001"},
    {unknown:"0"},
  ]){
    const {fetchImpl,calls}=makeMock({models:catalogue([model(TWO[0],prices)])});
    const r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl});
    assert.equal(r.models[0].status,"PRICE_UNVERIFIED",JSON.stringify(prices));
    assert.equal(r.models[1].status,"NOT_LISTED");
    assertAllRequestsAreReadOnly(calls);
  }
});
test("the exact five-second portrait setting is required",async()=>{
  const invalid=[
    {...model(TWO[0]),supported_durations:[8,10]},
    {...model(TWO[0]),supported_aspect_ratios:["16:9"]},
    {...model(TWO[0]),supported_resolutions:["1080p"]},
    {...model(TWO[0]),supported_resolutions:null},
  ];
  for(const single of invalid){
    const {fetchImpl}=makeMock({models:catalogue([single])});
    const r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl});
    assert.equal(r.models[0].status,"SETTINGS_UNSUPPORTED");
    assert.equal(r.models[0].resolution,null);
    assert.equal(r.video_submission_allowed,false);
  }
});
test("catalogue duplicate IDs and malformed JSON fail closed",async()=>{
  let mock=makeMock({models:catalogue([model(TWO[0]),model(TWO[0])])});
  let r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl:mock.fetchImpl});
  assert.equal(r.models[0].status,"DUPLICATE_ID");
  assertAllRequestsAreReadOnly(mock.calls);
  mock=makeMock({catalogueBodyRaw:"PRIVATE_TOKEN {broken-json"});
  r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl:mock.fetchImpl});
  assert.equal(r.status,"CATALOGUE_UNAVAILABLE");
  privateDataNotInReport(r);
});
test("revoked key and non-200/redirect responses stop before catalogue",async()=>{
  for (const code of [301,302,401,403,404,429,500]){
    const mock=makeMock({keyStatus:code});
    const r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl:mock.fetchImpl});
    assert.equal(r.status,"KEY_AUTH_UNVERIFIED");
    assert.equal(r.authenticated,false);
    assert.equal(mock.calls.length,1);
    assertAllRequestsAreReadOnly(mock.calls);
  }
});
test("invalid /key response or management keys cannot check catalog",async()=>{
  for (const key of [
    null,{}, {data:{}}, {data:{is_free_tier:"true"}},
    {data:{is_free_tier:true,is_management_key:true}},
  ]){
    const mock=makeMock({key});
    const r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl:mock.fetchImpl});
    assert.equal(r.status,"KEY_AUTH_UNVERIFIED");
    assert.equal(mock.calls.length,1);
  }
});
test("catalogue API rejection never claims free availability",async()=>{
  for(const code of [301,401,402,403,404,429,500]){
    const mock=makeMock({catalogueStatus:code});
    const r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl:mock.fetchImpl});
    assert.equal(r.status,"CATALOGUE_UNAVAILABLE");
    assert.equal(r.authenticated,true);
    assert.ok(r.models.every(x=>x.status==="NOT_CHECKED"));
    assertAllRequestsAreReadOnly(mock.calls);
  }
});
test("network errors are sanitized and no provider-supplied endpoint is used",async()=>{
  const calls=[];
  const fetchImpl=async(url, options)=>{
    calls.push({url,options});
    if(calls.length===1)return Response.json(keyBody);
    throw Error("PRIVATE_TOKEN secret server failed https://attacker.example/");
  };
  const r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl});
  assert.equal(r.status,"CATALOGUE_UNAVAILABLE");
  assertAllRequestsAreReadOnly(calls);
  privateDataNotInReport(r);
});
test("the key can be valid without being free-tier; never approve POST",async()=>{
  const mock=makeMock({key:{data:{is_free_tier:false,is_management_key:false}}});
  const r=await readonlyWanVideoPreflight({apiKey:KEY,fetchImpl:mock.fetchImpl});
  assert.equal(r.authenticated,true);
  assert.equal(r.free_tier_key,false);
  assert.equal(r.video_submission_allowed,false);
  assert.equal(r.paid_requests_made,0);
});
