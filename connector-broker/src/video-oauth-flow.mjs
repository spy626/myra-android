/**
 * Slice 11: server-side GitHub OAuth web login with PKCE S256.
 * Browser -> GitHub -> fixed HTTPS callback. No Android tokens or Actions
 * dispatch. This module must only run in the separate staging worker.
 */
const E = new TextEncoder();
const MAX_JSON = 16384;
const makeRandom = () => {
  const n = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...n)).replace(/\+/g, "-")
    .replace(/\//g, "_").replace(/=+$/g, "");
};
const hexSha = async value => [...new Uint8Array(await crypto.subtle.digest(
  "SHA-256", E.encode(value)))].map(x=>x.toString(16).padStart(2,"0")).join("");
const base64urlSha = async value => {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", E.encode(value)));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g,"-")
    .replace(/\//g,"_").replace(/=+$/g,"");
};

function config(env) {
  const id = env?.VIDEO_GITHUB_CLIENT_ID, secret = env?.VIDEO_GITHUB_CLIENT_SECRET;
  const callback = env?.VIDEO_GITHUB_CALLBACK_URL;
  if (typeof id !== "string" || !/^[A-Za-z0-9_-]{10,100}$/.test(id) ||
      typeof secret !== "string" || !/^[A-Za-z0-9_-]{20,200}$/.test(secret) ||
      typeof callback !== "string") throw Error("GitHub OAuth server credentials unavailable");
  let u;
  try { u = new URL(callback); } catch { throw Error("Invalid configured OAuth callback"); }
  if (u.protocol !== "https:" || u.username || u.password || u.hash ||
      u.search || u.pathname !== "/oauth/github/callback" ||
      u.hostname === "localhost" || u.port) throw Error("Non-public OAuth callback blocked");
  return {clientId:id, clientSecret:secret, callback:u.href};
}

async function boundedJson(response, label) {
  if (response.status !== 200 || response.redirected ||
      !response.headers?.get?.("content-type")?.toLowerCase().includes("application/json"))
    throw Error(label + " was not accepted");
  if (!response.body?.getReader) throw Error(label + " response missing");
  const reader = response.body.getReader();
  const chunks=[]; let bytes=0;
  try {
    for (;;) {
      const {done,value}=await reader.read();
      if (done) break;
      bytes+=value.length;
      if (bytes>MAX_JSON) throw Error(label+" reply too large");
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const all=new Uint8Array(bytes);let off=0;
  for(const chunk of chunks){all.set(chunk,off);off+=chunk.length;}
  return JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(all));
}

export async function beginBrowserGitHubOAuth({env,vault,nowSeconds}) {
  const cfg=config(env);
  if (!Number.isSafeInteger(nowSeconds) || nowSeconds < 1000000000 ||
      !vault || typeof vault.begin !== "function")
    throw Error("Trusted OAuth start unavailable");
  const state=makeRandom(), verifier=makeRandom(), browserProof=makeRandom();
  const challenge=await base64urlSha(verifier);
  await vault.begin({state,verifier,cookieHash:await hexSha(browserProof),
    nowSeconds, expiresAt:nowSeconds+600});
  const url=new URL("https://github.com/login/oauth/authorize");
  url.searchParams.set("client_id",cfg.clientId);
  url.searchParams.set("redirect_uri",cfg.callback);
  url.searchParams.set("state",state);
  url.searchParams.set("code_challenge",challenge);
  url.searchParams.set("code_challenge_method","S256");
  url.searchParams.set("scope","read:user");
  return Object.freeze({authorizationUrl:url.href,browserProof});
}

export async function finishBrowserGitHubOAuth({
  env,vault,nowSeconds,state,code,browserProof,fetchImpl,
}) {
  const cfg=config(env);
  if (!Number.isSafeInteger(nowSeconds) || nowSeconds < 1000000000 ||
      !vault || typeof vault.take !== "function" ||
      typeof vault.saveSession !== "function" || typeof fetchImpl !== "function")
    throw Error("Trusted OAuth callback unavailable");
  if (typeof state !== "string" || !/^[A-Za-z0-9_-]{32,128}$/.test(state) ||
      typeof code !== "string" || !/^[A-Za-z0-9_-]{8,512}$/.test(code) ||
      typeof browserProof !== "string" || !/^[A-Za-z0-9_-]{32,128}$/.test(browserProof))
    throw Error("Malformed OAuth callback parameters");
  // Atomic one-time state consumption BEFORE any Github token request.
  const verifier=await vault.take({state,cookieHash:await hexSha(browserProof),nowSeconds});
  const tokenResponse=await fetchImpl("https://github.com/login/oauth/access_token",{
    method:"POST",redirect:"manual",
    headers:{"Accept":"application/json","Content-Type":"application/json",
      "User-Agent":"LYRA-VideoStudio-StagingOAuth/1"},
    body:JSON.stringify({client_id:cfg.clientId,client_secret:cfg.clientSecret,
      code,redirect_uri:cfg.callback,code_verifier:verifier}),
  });
  const reply=await boundedJson(tokenResponse,"GitHub OAuth exchange");
  if (reply.error || typeof reply.access_token !== "string" ||
      !/^[A-Za-z0-9_-]{20,300}$/.test(reply.access_token) ||
      (reply.token_type && String(reply.token_type).toLowerCase() !== "bearer"))
    throw Error("GitHub OAuth token was not issued");
  // Recheck the actual user BEFORE storing any newly issued session.
  const user=await boundedJson(await fetchImpl("https://api.github.com/user",{
    method:"GET",redirect:"manual",
    headers:{"Authorization":"Bearer "+reply.access_token,
      "Accept":"application/vnd.github+json",
      "X-GitHub-Api-Version":"2022-11-28",
      "User-Agent":"LYRA-VideoStudio-StagingOAuth/1"},
  }),"GitHub user identity");
  if (!Number.isSafeInteger(user.id) || user.id<1 ||
      typeof user.login !== "string" || !/^[A-Za-z0-9-]{1,100}$/.test(user.login))
    throw Error("GitHub identity verification failed");
  const ttl=Number.isSafeInteger(reply.expires_in) && reply.expires_in>0
    ? Math.min(reply.expires_in,1800) : 1800;
  const sessionId=makeRandom();
  await vault.saveSession({sessionId,githubUserId:user.id,accessToken:reply.access_token,
    nowSeconds,expiresAt:nowSeconds+ttl});
  return Object.freeze({sessionId,maxAgeSeconds:ttl});
}
