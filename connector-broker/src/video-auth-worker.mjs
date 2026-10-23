/**
 * Isolated, DISABLED-BY-DEFAULT staging OAuth web entrypoint.
 * Does not change/deploy the installed LYRA connector src/index.js.
 * Routes GET /oauth/github/start, GET /oauth/github/callback and a
 * CSRF-protected POST /oauth/github/logout. No session introspection.
 * Absolutely NO GitHub Actions dispatch, authorization override or MP4 API.
 */
import {beginBrowserGitHubOAuth,finishBrowserGitHubOAuth} from "./video-oauth-flow.mjs";

const HASH_TEXT=new TextEncoder();
async function anonymousClientKey(request,env) {
  // On a separately approved Cloudflare Worker, this header is set by the
  // platform. It is a rate hint, NEVER user identity or authorization.
  const address=request.headers.get("cf-connecting-ip");
  if (!address || address.length>45 || address!==address.trim() ||
      address.includes(",")) throw Error("Cloudflare client address unavailable");
  if (address.includes(":")) {
    if (!/^[0-9a-fA-F:.]+$/.test(address) || address.split(":").length<3)
      throw Error("Invalid client address");
  } else {
    const parts=address.split(".");
    if (parts.length!==4 || parts.some(p=>!/^(0|[1-9][0-9]{0,2})$/.test(p) ||
        Number(p)>255)) throw Error("Invalid client address");
  }
  const hex=env?.VIDEO_AUTH_RATE_KEY_HEX;
  if (typeof hex!=="string" || !/^[0-9a-fA-F]{64}$/.test(hex))
    throw Error("Server-side limiter hash secret unavailable");
  const raw=Uint8Array.from(hex.match(/.{2}/g),b=>parseInt(b,16));
  const key=await crypto.subtle.importKey("raw",raw,{name:"HMAC",hash:"SHA-256"},false,["sign"]);
  const digest=new Uint8Array(await crypto.subtle.sign("HMAC",key,
    HASH_TEXT.encode(address.toLowerCase())));
  return Array.from(digest,b=>b.toString(16).padStart(2,"0")).join("");
}



const PENDING = "__Host-lyra_video_pending";
const SESSION = "__Host-lyra_video_session";

const HEADERS = {
  "Cache-Control":"no-store",
  "Referrer-Policy":"no-referrer",
  "X-Content-Type-Options":"nosniff",
  "Content-Security-Policy":"default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
};
const response=(body,status=200,extra={}) => new Response(JSON.stringify(body),{
  status,headers:{...HEADERS,"Content-Type":"application/json; charset=utf-8",...extra},
});

function cookieValue(request,name) {
  const raw=request.headers.get("cookie") || "";
  if (raw.length>4096) throw Error("Invalid browser cookie");
  const pairs=raw.split(";").map(v=>v.trim().split("="));
  const values=pairs.filter(p=>p.length===2 && p[0]===name).map(p=>p[1]);
  if (values.length!==1 || !/^[A-Za-z0-9_-]{32,128}$/.test(values[0]))
    throw Error("Missing/ambiguous OAuth browser proof");
  return values[0];
}
const cookie=(name,value,age) =>
  name+"="+value+"; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age="+age;

export default {
  async fetch(request,env) {
    try {
      // No routes activate based only on an unreviewed env binding or phone data.
      if (env?.VIDEO_AUTH_ENABLED!=="true" ||
          env?.VIDEO_AUTH_DEPLOYMENT_APPROVED!=="true") {
        return response({error:"video_auth_not_deployed"},503);
      }
      const url=new URL(request.url);
      const logout = url.pathname==="/oauth/github/logout" && !url.search &&
        request.method==="POST";
      if ((request.method!=="GET" && !(logout && request.method==="POST")) ||
          url.protocol!=="https:" ||
          typeof env?.VIDEO_AUTH_PUBLIC_ORIGIN!=="string" ||
          url.origin!==env.VIDEO_AUTH_PUBLIC_ORIGIN ||
          new URL(env.VIDEO_GITHUB_CALLBACK_URL).origin!==url.origin)
        return response({error:"request_not_allowed"},403);
      if (!env.VIDEO_OAUTH_VAULT ||
          typeof env.VIDEO_OAUTH_VAULT.idFromName!=="function" ||
          typeof env.VIDEO_OAUTH_VAULT.get!=="function")
        return response({error:"vault_not_configured"},503);
      const vault=env.VIDEO_OAUTH_VAULT.get(
        env.VIDEO_OAUTH_VAULT.idFromName("video:oauth-vault:v1")
      );
      const nowSeconds=Math.floor(Date.now()/1000);
      // Bound requests BEFORE durable OAuth state creation or GitHub traffic.
      // The singleton global budget also limits rotating client addresses.
      const route=url.pathname==="/oauth/github/start" && !url.search
        ? "start" : url.pathname==="/oauth/github/callback" ? "callback" : null;
      if (route) {
        const limiter=env?.VIDEO_AUTH_RATE_GATE;
        if (!limiter || typeof limiter.idFromName!=="function" ||
            typeof limiter.get!=="function")
          return response({error:"rate_gate_unavailable"},503);
        const gate=limiter.get(limiter.idFromName("video:oauth-global-rate:v1"));
        if (!gate || typeof gate.reserveAttempt!=="function")
          return response({error:"rate_gate_unavailable"},503);
        const permitted=await gate.reserveAttempt({
          route,sourceKey:await anonymousClientKey(request,env),nowSeconds,
        });
        if (permitted!==true)
          return response({error:"rate_limited"},429,{"Retry-After":"600"});
      }
      if (logout) {
        // Revoke ONLY the server-held session. A cross-origin form POST must
        // never be able to sign out the user's browser (logout CSRF).
        const origin=request.headers.get("origin");
        const site=request.headers.get("sec-fetch-site");
        if (origin!==url.origin || (site!==null && site!=="same-origin") ||
            request.body!==null) {
          return response({error:"request_not_allowed"},403);
        }
        if (typeof vault.revoke!=="function")
          return response({error:"vault_not_configured"},503);
        await vault.revoke(cookieValue(request,SESSION));
        // Only clear browser cookies AFTER durable server-side revocation.
        const headers=new Headers({...HEADERS,"Content-Type":"application/json; charset=utf-8"});
        headers.append("Set-Cookie",cookie(SESSION,"",0));
        headers.append("Set-Cookie",cookie(PENDING,"",0));
        return new Response(JSON.stringify({signed_out:true,render_submitted:false}),
          {status:200,headers});
      }
      if (url.pathname==="/oauth/github/start" && !url.search) {
        const result=await beginBrowserGitHubOAuth({env,vault,nowSeconds});
        return new Response(null,{status:303,headers:{
          ...HEADERS,"Location":result.authorizationUrl,
          "Set-Cookie":cookie(PENDING,result.browserProof,600),
        }});
      }
      if (url.pathname==="/oauth/github/callback") {
        // No arbitrary redirect_after or frontend return_to parameter.
        if ([...url.searchParams.keys()].some(k=>!["code","state"].includes(k)) ||
            url.searchParams.getAll("code").length!==1 ||
            url.searchParams.getAll("state").length!==1)
          return response({error:"invalid_oauth_callback"},400);
        const result=await finishBrowserGitHubOAuth({
          env,vault,nowSeconds,code:url.searchParams.get("code"),
          state:url.searchParams.get("state"),
          browserProof:cookieValue(request,PENDING),fetchImpl:fetch,
        });
        const headers=new Headers({...HEADERS,"Content-Type":"application/json; charset=utf-8"});
        headers.append("Set-Cookie",cookie(PENDING,"",0));
        headers.append("Set-Cookie",cookie(SESSION,result.sessionId,result.maxAgeSeconds));
        return new Response(JSON.stringify({
          authorized:true,
          render_submitted:false,
          next:"GitHub login completed in staging browser only; no video job was started.",
        }),{status:200,headers});
      }
      return response({error:"not_found"},404);
    } catch {
      // Never echo code, token, verifier, session ID or Github user profile.
      return response({error:"oauth_request_rejected"},400);
    }
  },
};
