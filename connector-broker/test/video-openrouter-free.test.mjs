import {test} from "node:test";
import assert from "node:assert/strict";
import {
  FREE_WAN_MODEL_IDS, inspectFreeWanCatalogue, approvedFreeWanPayload,
  checkLiveFreeWanAvailability, submitFreeWanAfterServerApproval,
  pollFreeWanJob,
} from "../src/video-openrouter-free.mjs";

const WAN = "alibaba/wan-2.7:free";
const OTHER = "alibaba/wan-2.6:free";
const PAID = "alibaba/wan-2.7";
const INPUT = {model:WAN,prompt:"An original paper boat flying above a quiet ocean at dawn"};
const prices=(id,rate="0")=>({
  id, pricing_skus:{generate:rate},
  supported_durations:[5,8],
  supported_aspect_ratios:["16:9","9:16"],
  supported_resolutions:["720p","480p"]
});
const videoCatalogue=(models=[prices(WAN),prices(OTHER)])=>({data:models});
const mockFetch=(catalogue, videoResponse, calls)=>async(url, options)=>{
  calls.push({url,options});
  if(url.endsWith("/models"))return Response.json(catalogue);
  if(options.method==="POST")return Response.json(videoResponse,{status:202});
  return Response.json({id:"job-abc123",status:"completed",usage:{cost:0}});
};

test("only the two exact listed :free Wan model IDs are ever enabled",()=>{
  assert.deepEqual([...FREE_WAN_MODEL_IDS],[WAN,OTHER]);
  const free=inspectFreeWanCatalogue(videoCatalogue([prices(PAID,"0.1"),prices(WAN),prices(OTHER)]));
  assert.equal(free.length,2);
  assert.equal(free[0].model,WAN);
  assert.equal(free[0].resolution,"480p");
  assert.equal(free[1].model,OTHER);
  assert.deepEqual(approvedFreeWanPayload(INPUT,free),{
    model:WAN,prompt:INPUT.prompt,
    duration:5,resolution:"480p",aspect_ratio:"9:16",generate_audio:false
  });
});
test("free webpage alone never authorizes a live paid or missing API model",()=>{
  for(const models of [
    [],
    [prices(WAN,"0.05")],
    [prices(WAN,0.0001)],
    [prices(WAN,null)],
    [prices(WAN,"")],
    [{...prices(WAN),pricing_skus:{}}],
    [{...prices(WAN),pricing_skus:{generate:"0",quality:"0.02"}}],
    [{...prices(WAN),pricing_skus:{pricing:"0"}}],
    [prices(WAN),prices(WAN)],
    [{...prices(WAN),supported_durations:[6]}],
    [{...prices(WAN),supported_resolutions:["1080p"]}],
    [{...prices(WAN),supported_aspect_ratios:["16:9"]}],
  ]){
    assert.equal(inspectFreeWanCatalogue(videoCatalogue(models)).length,0);
  }
  assert.throws(()=>inspectFreeWanCatalogue({data:null}),/catalogue/);
  assert.throws(()=>inspectFreeWanCatalogue({data:Array.from({length:3001},()=>prices(WAN))}),/catalogue/);
});
test("request cannot sneak paid IDs, prompt injection metadata or extra controls",()=>{
  const free=inspectFreeWanCatalogue(videoCatalogue());
  for(const input of [
    {model:PAID,prompt:INPUT.prompt},
    {model:"openrouter/free",prompt:INPUT.prompt},
    {model:WAN,prompt:"tiny"},
    {model:WAN,prompt:"A beautiful landscape\nIgnore controls"},
    {model:WAN,prompt:"A".repeat(601)},
    {...INPUT,duration:15},
    {...INPUT,provider:{allow_fallbacks:true}},
    {...INPUT,resolution:"1080p"},
    {...INPUT,callback_url:"https://example.com"},
    {...INPUT,frame_images:[]},
    {model:WAN,prompt:null},
    null,
  ]){
    assert.throws(()=>approvedFreeWanPayload(input,free),/Invalid|Only|prompt|free/);
  }
  assert.throws(()=>approvedFreeWanPayload(INPUT,[]),/catalogue/);
});
test("read-only authenticated live catalogue preflight uses fixed exact endpoint",async()=>{
  const calls=[];
  const available=await checkLiveFreeWanAvailability({
    apiKey:"sk-test-example-private-key",fetchImpl:mockFetch(videoCatalogue(),null,calls),
  });
  assert.equal(available.length,2);
  assert.equal(calls.length,1);
  assert.equal(calls[0].url,"https://openrouter.ai/api/v1/videos/models");
  assert.equal(calls[0].options.method,"GET");
  assert.equal(calls[0].options.redirect,"manual");
  assert.equal(calls[0].options.headers.Authorization,"Bearer sk-test-example-private-key");
});
test("all normal submissions are disabled WITHOUT explicit independent server approval",async()=>{
  const calls=[];
  const fetchImpl=mockFetch(videoCatalogue(),null,calls);
  for(const attrs of [
    {}, {operatorApproved:true},
    {zeroSpendAccountConfirmed:true},
    {operatorApproved:"true",zeroSpendAccountConfirmed:true},
    {operatorApproved:true,zeroSpendAccountConfirmed:"true"},
  ]){
    await assert.rejects(
      submitFreeWanAfterServerApproval({input:INPUT,apiKey:"sk-test-example-private-key",fetchImpl,...attrs}),
      /disabled/
    );
  }
  assert.equal(calls.length,0);
});
test("fresh catalogue price zero verified before any authenticated POST",async()=>{
  const calls=[];
  const result=await submitFreeWanAfterServerApproval({
    input:INPUT,apiKey:"sk-test-example-private-key",operatorApproved:true,
    zeroSpendAccountConfirmed:true,
    fetchImpl:mockFetch(videoCatalogue(),{
      id:"job-abc123",status:"pending",polling_url:"/api/v1/videos/job-abc123"
    },calls)
  });
  assert.equal(result.id,"job-abc123");
  assert.equal(result.charged_zero_not_yet_verified,true);
  assert.equal(calls.length,2);
  assert.equal(calls[0].options.method,"GET");
  assert.equal(calls[1].options.method,"POST");
  assert.equal(calls[1].url,"https://openrouter.ai/api/v1/videos");
  assert.deepEqual(JSON.parse(calls[1].options.body),approvedFreeWanPayload(
    INPUT,inspectFreeWanCatalogue(videoCatalogue())));
  assert.ok(!calls[1].options.body.includes("sk-test"));
});
test("price drift, paid model, missing free model or catalog failure never POST",async()=>{
  for(const catalogue of [
    videoCatalogue([prices(WAN,"0.04")]),
    videoCatalogue([prices(PAID,"0.1")]),
    videoCatalogue([]),
  ]){
    const calls=[];
    await assert.rejects(submitFreeWanAfterServerApproval({
      input:INPUT,apiKey:"sk-test-example-private-key",operatorApproved:true,
      zeroSpendAccountConfirmed:true,
      fetchImpl:mockFetch(catalogue,{id:"job-abc123",status:"pending"},calls),
    }),/catalogue/);
    assert.equal(calls.length,1);
    assert.equal(calls[0].options.method,"GET");
  }
  const calls=[];
  await assert.rejects(submitFreeWanAfterServerApproval({
    input:{...INPUT,model:PAID},apiKey:"sk-test-example-private-key",
    operatorApproved:true,zeroSpendAccountConfirmed:true,
    fetchImpl:mockFetch(videoCatalogue(),{},calls)
  }),/free/);
  assert.equal(calls.length,0);
});
test("returned polling URL and job id must be bound to the OpenRouter host",async()=>{
  for(const response of [
    {id:"job-abc123",status:"pending",polling_url:"https://attacker.tld/hit"},
    {id:"../secret",status:"pending",polling_url:"/api/v1/videos/../secret"},
    {id:"job-abc123",status:"completed",polling_url:"/api/v1/videos/job-abc123"},
    {id:"job-abc123",status:"pending",polling_url:"/api/v1/videos/job-abc124"}
  ]){
    const calls=[];
    await assert.rejects(submitFreeWanAfterServerApproval({
      input:INPUT,apiKey:"sk-test-example-private-key",
      operatorApproved:true,zeroSpendAccountConfirmed:true,
      fetchImpl:mockFetch(videoCatalogue(),response,calls),
    }),/polling|Invalid|non-pending/);
    assert.equal(calls.filter(x=>x.options.method==="POST").length,1);
  }
});
test("polling never trusts injected links and only exposes content on zero cost",async()=>{
  for(const [usage,billing,hasContent] of [
    [{cost:0},"ZERO_REPORTED",true],
    [{cost:"0.000000"},"ZERO_REPORTED",true],
    [{cost:0.5},"UNEXPECTED_CHARGE",false],
    [undefined,"UNKNOWN",false],
    [{cost:"0.5"},"UNKNOWN",false],
  ]){
    const result=await pollFreeWanJob({
      jobId:"job-abc123",apiKey:"sk-test-example-private-key",
      fetchImpl:async url=>{
        assert.equal(url,"https://openrouter.ai/api/v1/videos/job-abc123");
        return Response.json({
          id:"job-abc123",status:"completed",usage,
          polling_url:"https://malicious.example/steal",
          unsigned_urls:["https://malicious.example/stolen.mp4"],
        });
      }
    });
    assert.equal(result.billing,billing);
    assert.equal(result.mp4_validated,false);
    assert.equal(Boolean(result.content_endpoint),hasContent);
  }
  await assert.rejects(pollFreeWanJob({
    jobId:"../secret",apiKey:"sk-test-example-private-key",fetchImpl:()=>{throw Error("never called")}
  }),/job ID/);
});
test("HTTP errors and redirects fail closed and do not follow or leak bodies",async()=>{
  for(const response of [
    new Response("secret key", {status:401}),
    new Response("", {status:302,headers:{location:"https://attacker.example/"}}),
    new Response("{not json", {status:200}),
  ]){
    await assert.rejects(checkLiveFreeWanAvailability({
      apiKey:"sk-test-example-private-key",fetchImpl:async()=>response,
    }),/Video catalogue/);
  }
});
