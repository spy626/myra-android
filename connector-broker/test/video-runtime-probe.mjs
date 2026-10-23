/**
 * SLICE 18 TEST-ONLY Worker entrypoint. Never referenced by the staging or
 * production Wrangler config. This is launched ONLY on loopback by CI using
 * an ephemeral Wrangler config, fake credentials, and locally backed DOs.
 * It MUST NOT be deployed. No public OAuth login or GitHub network calls.
 */
import {
  VideoOAuthVault,VideoOAuthRateGate,VideoRenderOwnerDurableObject,
} from "../src/video-auth-worker-cloudflare.mjs";
export {VideoOAuthVault,VideoOAuthRateGate,VideoRenderOwnerDurableObject};

const state="S".repeat(43),proof="a".repeat(64),otherProof="b".repeat(64);
const verifier="V".repeat(43),sessionId="s_0123456789abcdef0123456789abcdef";
const token="ghu_fake_local_ci_token_012345678900";
const source="c".repeat(64);
const ownerId=876543;
const nonce="00000000-0000-4000-8000-000000000818";
const fail=()=>new Response(JSON.stringify({passed:false}),{
  status:500,headers:{"Cache-Control":"no-store","Content-Type":"application/json"},
});
const succeed=phase=>Response.json({passed:true,phase,local_only:true,deployed:false},{
  headers:{"Cache-Control":"no-store"},
});
const assert=(condition,message)=>{if(!condition)throw Error(message);};
const rejected=async promise=>{try{await promise;return false;}catch{return true;}};
function objects(env){
  return {
    vault:env.VIDEO_OAUTH_VAULT.get(env.VIDEO_OAUTH_VAULT.idFromName("ci-vault")),
    rate:env.VIDEO_AUTH_RATE_GATE.get(env.VIDEO_AUTH_RATE_GATE.idFromName("ci-global-rate")),
    owner:env.VIDEO_OWNER_LEDGER.get(env.VIDEO_OWNER_LEDGER.idFromName("ci-owner-876543")),
  };
}
export default {
  async fetch(request,env){
    const url=new URL(request.url);
    const guarded=[
      ["method",request.method==="POST"],
      ["pathname",url.pathname==="/__slice18_local_rpc"],
      ["query",!url.search],
      ["loopback",url.hostname==="127.0.0.1"],
      ["port",url.port==="8788"],
      ["local_flag",request.headers.get("x-lyra-slice18-local")==="enabled"],
      ["body_absent",request.body===null],
    ];
    const failed=guarded.find(([,allowed])=>!allowed);
    if(failed) return Response.json({error:"local_probe_guard",field:failed[0]},{
      status:404,headers:{"Cache-Control":"no-store"},
    });
    if(env?.VIDEO_AUTH_ENABLED!=="false" ||
       env?.VIDEO_AUTH_DEPLOYMENT_APPROVED!=="false" ||
       env?.VIDEO_SESSION_KEY_HEX!=="a".repeat(64))
      return fail();
    const {vault,rate,owner}=objects(env);
    const now=Math.floor(Date.now()/1000);
    try {
      if(url.pathname!=="/__slice18_local_rpc")return fail();
      const phase=request.headers.get("x-lyra-slice18-phase");
      if(phase==="init"){
        assert(await vault.begin({
          state,verifier,cookieHash:proof,nowSeconds:now,expiresAt:now+120,
        })===true,"pending creation");
        assert(await vault.saveSession({
          sessionId,githubUserId:ownerId,accessToken:token,
          nowSeconds:now,expiresAt:now+180,
        })===true,"encrypted session creation");
        const attempts=await Promise.all(Array.from({length:10},()=>
          rate.reserveAttempt({route:"start",sourceKey:source,nowSeconds:now})));
        assert(attempts.filter(Boolean).length===4,"atomic DO client rate cap");
        assert(await owner.reserveFromTrustedBroker({
          ownerId,sessionId,nonce,projectId:"ci_only_scene",
          nowSeconds:now,expiresAt:now+120,maxActive:1,maxPerUtcDay:3,
        })===true,"owner reservation");
        return succeed(phase);
      }
      if(phase==="verify"){
        assert(await rejected(vault.take({
          state,cookieHash:otherProof,nowSeconds:now,
        })),"wrong browser must fail");
        const loaded=await vault.load(sessionId);
        assert(loaded?.accessToken===token && loaded.githubUserId===ownerId,
          "encrypted session durability across HTTP requests");
        assert(await vault.take({state,cookieHash:proof,nowSeconds:now})===verifier,
          "one-time state consumption");
        assert(await rejected(vault.take({state,cookieHash:proof,nowSeconds:now})),
          "state replay must fail");
        assert(await rate.reserveAttempt({
          route:"start",sourceKey:source,nowSeconds:now,
        })===false,"rate budget persists between HTTP requests");
        assert((await owner.readFromTrustedBroker(ownerId,now)).dailyUsed===1,
          "render quota persists between HTTP requests");
        assert(await vault.revoke(sessionId)===true,"durable logout");
        assert(await vault.load(sessionId)===null,"revoked session remains invalid");
        return succeed(phase);
      }
      if(phase==="replay"){
        assert(await rejected(vault.take({state,cookieHash:proof,nowSeconds:now})),
          "second request cannot replay state");
        assert(await vault.load(sessionId)===null,"session cannot be restored");
        assert(await owner.reserveFromTrustedBroker({
          ownerId,sessionId,nonce,projectId:"ci_only_scene",
          nowSeconds:now,expiresAt:now+120,maxActive:1,maxPerUtcDay:3,
        })===false,"owner nonce replay must fail");
        return succeed(phase);
      }
      return new Response(null,{status:404});
    }catch{
      // Never echo raw token, session, proof or OAuth state.
      return fail();
    }
  },
};
