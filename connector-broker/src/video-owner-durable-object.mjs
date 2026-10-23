import {VideoOwnerLedger} from "./video-owner-ledger.mjs";

/**
 * Slice 11 — class export for a separately deployable SQLite-backed DO.
 * No user-facing HTTP endpoint. A future trusted broker must choose the
 * DO namespace using a server-verified GitHub numeric owner, never phone data.
 */
export class VideoRenderOwnerDurableObject {
  constructor(ctx, env) {
    if (!ctx?.storage?.transaction) throw Error("Durable storage unavailable");
    this.ctx = ctx;
    this.env = env;
  }
  async reserveFromTrustedBroker(request) {
    // Trust boundary MUST be enforced by a future caller: never expose
    // this RPC directly through an unverified public endpoint.
    return new VideoOwnerLedger(this.ctx.storage, request.ownerId).reserve(request);
  }
  async readFromTrustedBroker(ownerId, nowSeconds) {
    return new VideoOwnerLedger(this.ctx.storage, ownerId)
      .stateForVerifiedOwner(ownerId, nowSeconds);
  }
}
