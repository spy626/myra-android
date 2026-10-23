/**
 * Cloudflare-only staging entrypoint. Cloudflare RPC requires classes that
 * inherit from the platform DurableObject base class (2026 runtime contract).
 * Node-tested storage adapters remain environment-independent.
 * No production broker route or GitHub dispatch is added here.
 */
import {DurableObject} from "cloudflare:workers";
import {VideoOAuthRateGate as RateGateAdapter} from "./video-auth-rate-limit.mjs";
import worker from "./video-auth-worker.mjs";
import {VideoOAuthVault as SessionVaultAdapter} from "./video-oauth-vault.mjs";
import {VideoRenderOwnerDurableObject as OwnerLedgerAdapter} from "./video-owner-durable-object.mjs";

export default worker;

export class VideoOAuthVault extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.adapter = new SessionVaultAdapter(ctx, env);
  }
  begin(input) { return this.adapter.begin(input); }
  take(input) { return this.adapter.take(input); }
  saveSession(input) { return this.adapter.saveSession(input); }
  load(sessionId) { return this.adapter.load(sessionId); }
  revoke(sessionId) { return this.adapter.revoke(sessionId); }
  alarm() { return this.adapter.cleanupExpired(Math.floor(Date.now()/1000)); }
}

export class VideoRenderOwnerDurableObject extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.adapter = new OwnerLedgerAdapter(ctx, env);
  }
  reserveFromTrustedBroker(input) { return this.adapter.reserveFromTrustedBroker(input); }
  readFromTrustedBroker(ownerId, nowSeconds) {
    return this.adapter.readFromTrustedBroker(ownerId, nowSeconds);
  }
}

/** Strictly one internal Durable Object namespace for rate limits. */
export class VideoOAuthRateGate extends DurableObject {
  constructor(ctx,env) {
    super(ctx,env);
    this.adapter=new RateGateAdapter(ctx);
  }
  reserveAttempt(input) { return this.adapter.reserveAttempt(input); }
  alarm() { return this.adapter.cleanupExpired(Math.floor(Date.now()/1000)); }
}
