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
    if (!ctx?.storage || typeof ctx.storage.transaction !== "function")
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
    return this.storage.transaction(async tx => {
      const record = await tx.get(key);
      // A mismatched browser proof must not consume the rightful browser's state.
      if (!record || record.cookieHash !== cookieHash)
        throw Error("OAuth browser binding mismatch");
      await tx.delete(key);
      if (record.expiresAt <= nowSeconds) throw Error("OAuth state expired");
      if (!VERIFIER.test(record.verifier)) throw Error("OAuth verifier is invalid");
      return record.verifier;
    });
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

  async revoke(sessionId) {
    if (typeof sessionId !== "string" || !SESSION.test(sessionId)) return false;
    await this.storage.delete("oauth:session:" + await sha(sessionId));
    return true;
  }
}
