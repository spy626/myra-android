/**
 * Isolated, DISABLED-BY-DEFAULT staging OAuth web entrypoint.
 * Does not change/deploy the installed LYRA connector src/index.js.
 * Routes only GET /oauth/github/start and GET /oauth/github/callback.
 * Absolutely NO GitHub Actions dispatch, authorization override or MP4 API.
 */
import {beginBrowserGitHubOAuth,finishBrowserGitHubOAuth} from "./video-oauth-flow.mjs";


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
      if (request.method!=="GET" || url.protocol!=="https:" ||
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
