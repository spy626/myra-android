/**
 * Slice 11: staging-only Cloudflare Durable Object OAuth state + session vault.
 * No public API methods are routed from the existing LYRA connector.
 * A user OAuth token is AES-GCM encrypted at rest with VIDEO_SESSION_KEY_HEX;
 * the opaque cookie handle itself is stored only as a SHA-256 key.
 */
const e = new TextEncoder();
const SESSION = /^[A-Za-z0-9_-]{32,128}$/;
const STATE = /^[A-Za-z0-9_-]{32,128}$/;
const VERIFIER = /^[A-Za-z0-9_~-]{43,128}$/;
const PROOF = /^[0-9a-f]{64}$/;
const MAX_PENDING_SECONDS = 600;
const MAX_SESSION_SECONDS = 1800;

const sha = async v => [...new Uint8Array(await crypto.subtle.digest("SHA-256", e.encode(v)))]
  .map(x => x.toString(16).padStart(2, "0")).join("");

export class VideoOAuthVault {
  constructor(ctx, env) {
    if (!ctx?.storage || typeof ctx.storage.transaction !== "function" ||
        typeof ctx.storage.getAlarm !== "function" ||
        typeof ctx.storage.setAlarm !== "function" ||
        typeof ctx.storage.list !== "function")
      throw Error("Durable transactional storage is required");
    this.storage = ctx.storage;
    this.env = env;
  }

  async key() {
    const raw = this.env?.VIDEO_SESSION_KEY_HEX;
    if (typeof raw !== "string" || !/^[0-9a-fA-F]{64}$/.test(raw))
      throw Error("Server-side AES-256 vault key missing");
    const bytes = Uint8Array.from(raw.match(/.{2}/g), h => parseInt(h, 16));
    return crypto.subtle.importKey("raw", bytes, {name:"AES-GCM"}, false, ["encrypt","decrypt"]);
  }

  async begin({state, verifier, cookieHash, nowSeconds, expiresAt}) {
    if (typeof state !== "string" || !STATE.test(state) ||
        typeof verifier !== "string" || !VERIFIER.test(verifier) ||
        typeof cookieHash !== "string" || !PROOF.test(cookieHash) ||
        !Number.isSafeInteger(nowSeconds) ||
        !Number.isSafeInteger(expiresAt) ||
        nowSeconds < 1000000000 || expiresAt <= nowSeconds ||
        expiresAt > nowSeconds + MAX_PENDING_SECONDS) throw Error("Invalid OAuth transaction");
    const key = "oauth:pending:" + await sha(state);
    // Fail closed before storing any untracked OAuth state.
    await this.scheduleExpiry(expiresAt);
    return this.storage.transaction(async tx => {
      if (await tx.get(key) !== undefined) throw Error("OAuth state already used");
      await tx.put(key, {verifier, cookieHash, expiresAt});
      return true;
    });
  }

  async take({state, cookieHash, nowSeconds}) {
    if (typeof state !== "string" || !STATE.test(state) ||
        typeof cookieHash !== "string" || !PROOF.test(cookieHash) ||
        !Number.isSafeInteger(nowSeconds) || nowSeconds < 1000000000)
      throw Error("Invalid OAuth callback");
    const key = "oauth:pending:" + await sha(state);
    // Commit the one-time state deletion before raising an expiry error:
    // throwing from inside a Durable Object transaction ROLLS BACK deletion.
    const result = await this.storage.transaction(async tx => {
      const record = await tx.get(key);
      // Wrong browser must not consume a legitimate user's pending login.
      if (!record || record.cookieHash !== cookieHash)
        throw Error("OAuth browser binding mismatch");
      await tx.delete(key);
      return {expired: record.expiresAt <= nowSeconds,
        verifier: record.verifier};
    });
    if (result.expired) throw Error("OAuth state expired");
    if (!VERIFIER.test(result.verifier)) throw Error("OAuth verifier is invalid");
    return result.verifier;
  }

  async saveSession({sessionId, githubUserId, accessToken, nowSeconds, expiresAt}) {
    if (typeof sessionId !== "string" || !SESSION.test(sessionId) ||
        !Number.isSafeInteger(githubUserId) || githubUserId < 1 ||
        typeof accessToken !== "string" || !/^[A-Za-z0-9_-]{20,300}$/.test(accessToken) ||
        !Number.isSafeInteger(nowSeconds) || nowSeconds < 1000000000 ||
        !Number.isSafeInteger(expiresAt) || expiresAt <= nowSeconds ||
        expiresAt > nowSeconds + MAX_SESSION_SECONDS)
      throw Error("Invalid GitHub OAuth session");
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
      {name:"AES-GCM", iv}, await this.key(), e.encode(accessToken)
    ));
    const record = {kind:"server-issued-github-oauth", githubUserId, expiresAt,
      iv:Array.from(iv), ciphertext:Array.from(ciphertext)};
    const key = "oauth:session:" + await sha(sessionId);
    await this.scheduleExpiry(expiresAt);
    await this.storage.transaction(async tx => {
      if (await tx.get(key) !== undefined) throw Error("Session collision");
      await tx.put(key, record);
    });
    return true;
  }

  /** The ONLY session read path used by the Slice 10 /user verifier. */
  async load(sessionId) {
    if (typeof sessionId !== "string" || !SESSION.test(sessionId)) return null;
    const key = "oauth:session:" + await sha(sessionId);
    const record = await this.storage.get(key);
    if (!record) return null;
    const now = Math.floor(Date.now()/1000);
    if (!Number.isSafeInteger(record.expiresAt) || record.expiresAt <= now) {
      await this.storage.delete(key);
      return null;
    }
    if (record.kind !== "server-issued-github-oauth" ||
        !Number.isSafeInteger(record.githubUserId) ||
        !Array.isArray(record.iv) || record.iv.length !== 12 ||
        !Array.isArray(record.ciphertext) || record.ciphertext.length < 20)
      throw Error("OAuth vault record invalid");
    const plain = await crypto.subtle.decrypt(
      {name:"AES-GCM", iv:new Uint8Array(record.iv)}, await this.key(),
      new Uint8Array(record.ciphertext)
    );
    return {kind:"server-issued-github-oauth", id:sessionId,
      githubUserId:record.githubUserId, expiresAt:record.expiresAt,
      accessToken:new TextDecoder("utf-8", {fatal:true}).decode(plain)};
  }

  async scheduleExpiry(expiresAt) {
    if (!Number.isSafeInteger(expiresAt) || expiresAt<1000000000)
      throw Error("Invalid OAuth expiry");
    const target=expiresAt*1000;
    const existing=await this.storage.getAlarm();
    if (existing===null || existing===undefined || existing>target)
      await this.storage.setAlarm(target);
  }

  // Invoked only by the Cloudflare platform alarm, NOT an HTTP route.
  async cleanupExpired(nowSeconds) {
    if (!Number.isSafeInteger(nowSeconds) || nowSeconds<1000000000)
      throw Error("Trusted cleanup time required");
    let pending=0,session=0,next=null;
    for (const prefix of ["oauth:pending:","oauth:session:"]) {
      const rows=await this.storage.list({prefix});
      if (!(rows instanceof Map)) throw Error("OAuth storage enumeration unavailable");
      for (const [key,item] of rows) {
        if (!new RegExp("^"+prefix+"[0-9a-f]{64}$").test(key))
          throw Error("Malformed OAuth storage key");
        if (!item || !Number.isSafeInteger(item.expiresAt) ||
            item.expiresAt<1000000000)
          throw Error("Malformed OAuth expiry record");
        if (item.expiresAt>nowSeconds) {
          next=next===null ? item.expiresAt : Math.min(next,item.expiresAt);
          continue;
        }
        const removed=await this.storage.transaction(async tx=>{
          // Re-check after listing, so concurrent state consumption/revocation
          // cannot cause unsafe cleanup of a changed record.
          const current=await tx.get(key);
          if (current===undefined) return false;
          if (!Number.isSafeInteger(current.expiresAt) ||
              current.expiresAt<1000000000)
            throw Error("Malformed OAuth expiry record");
          if (current.expiresAt>nowSeconds) return false;
          await tx.delete(key);
          return true;
        });
        if (removed) {
          if (prefix==="oauth:pending:") pending++; else session++;
        }
      }
    }
    if (next!==null) await this.scheduleExpiry(next);
    return Object.freeze({pending,session});
  }

  async revoke(sessionId) {
    if (typeof sessionId !== "string" || !SESSION.test(sessionId)) return false;
    await this.storage.delete("oauth:session:" + await sha(sessionId));
    return true;
  }
}
