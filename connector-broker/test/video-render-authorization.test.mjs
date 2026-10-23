import { test } from "node:test";
import assert from "node:assert/strict";
import {
  approvedRenderInputs,
  approvedRenderFingerprint,
  issueVideoRenderApproval,
  verifyAndReserveVideoRender,
} from "../src/video-render-authorization.mjs";

const input = Object.freeze({
  project_id: "mini_house_demo", floors: "1", color: "blue",
  roof: "gable", garden: "no", target: "30",
});
const owner = Object.freeze({
  kind: "verified-github-oauth", githubUserId: 39213,
  sessionId: "s_0123456789abcdef0123456789abcdef",
});
const secret = "sample-only-not-deployed-" + "x".repeat(56);
const now = 1_795_000_000;
const golden = "7a15c74a1779d3aa9ac0ccb6519d8d605a079840f5d25d3b86e0520924c6782e";

// TEST-ONLY in-memory fake. Production MUST use an atomic durable adapter.
class FakeAtomicStore {
  constructor() { this.used = new Set(); this.active = new Set(); this.daily = new Map(); }
  async reserve({ownerId, nonce, nowSeconds, maxActive, maxPerUtcDay}) {
    const day = Math.floor(nowSeconds / 86400), quotaKey = ownerId + ":" + day;
    const usedKey = ownerId + ":" + nonce;
    if (this.used.has(usedKey) || this.active.has(ownerId) ||
        (this.daily.get(quotaKey) || 0) >= maxPerUtcDay ||
        maxActive !== 1 || maxPerUtcDay !== 3) return false;
    this.used.add(usedKey);
    this.active.add(ownerId);
    this.daily.set(quotaKey, (this.daily.get(quotaKey) || 0) + 1);
    return true;
  }
  finish(ownerId) { this.active.delete(ownerId); }
}

const issue = (overrides = {}) => issueVideoRenderApproval({
  inputs: input, principal: owner, explicitApproval: true,
  signingSecret: secret, nowSeconds: now, ...overrides,
});
const reserve = (ticket, store = new FakeAtomicStore(), overrides = {}) =>
  verifyAndReserveVideoRender({
    ticket, principal: owner, signingSecret: secret, nowSeconds: now + 10,
    durableReservationStore: store, verifiedDeployment: true,
    verifiedActionsPermission: true, ...overrides,
  });

test("approved six-field fingerprint matches prior Android + Python golden", async () => {
  assert.deepEqual(approvedRenderInputs(input), input);
  assert.equal(await approvedRenderFingerprint(input), golden);
  for (const key of Object.keys(input)) {
    const changed = { ...input, [key]: key === "project_id" ? "different" :
      key === "floors" ? "2" : key === "color" ? "cream" :
      key === "roof" ? "flat" : key === "garden" ? "yes" : "60" };
    assert.notEqual(await approvedRenderFingerprint(changed), golden);
  }
});

test("untrusted or overbroad payloads do not become jobs", async () => {
  for (const item of [
    {...input, prompt: "private conversation"}, {...input, token: "bad"},
    {...input, project_id: "../main"}, {...input, floors: 3},
    {...input, color: "purple"}, {...input, target: "120"}, {...input, garden: "maybe"},
  ]) assert.throws(() => approvedRenderInputs(item));
  await assert.rejects(issue({ explicitApproval: false }), /approval/);
  await assert.rejects(issue({ principal: {...owner, kind: "device-asserted"} }), /Verified/);
  await assert.rejects(issue({ signingSecret: "123" }), /secret/);
});

test("approval is HMAC-bound to owner, session, inputs and workflow", async () => {
  const ticket = await issue();
  const decision = await reserve(ticket);
  assert.equal(decision.state, "RESERVED_NOT_DISPATCHED");
  assert.equal(decision.ownerId, owner.githubUserId);
  assert.equal(decision.fingerprint, golden);
  assert.equal(decision.workflow, "video-studio-slice-6.yml");
  assert.equal(decision.ref, "agent/lyra-video-studio-slice-6");
  assert.equal(decision.inputs.project_id, input.project_id);
  assert.equal(ticket.includes(secret), false);
  assert.equal(ticket.includes("github_pat"), false);
});

test("tampering, expiration, missing permissions, wrong user/session fail closed", async () => {
  const ticket = await issue();
  const [payload, sig] = ticket.split(".");
  await assert.rejects(reserve(payload.slice(0, -1) + "A." + sig), /signature|Invalid|Malformed/);
  await assert.rejects(reserve(payload + ".AAAA"), /Invalid/);
  await assert.rejects(reserve(ticket, new FakeAtomicStore(), {
    principal: {...owner, githubUserId: 99991},
  }), /another authenticated session/);
  await assert.rejects(reserve(ticket, new FakeAtomicStore(), {
    principal: {...owner, sessionId: "s_ffffffffffffffffffffffffffffffff"},
  }), /another authenticated session/);
  await assert.rejects(reserve(ticket, new FakeAtomicStore(), {
    nowSeconds: now + 301,
  }), /Expired/);
  await assert.rejects(reserve(ticket, new FakeAtomicStore(), {
    verifiedDeployment: false,
  }), /deployment/);
  await assert.rejects(reserve(ticket, new FakeAtomicStore(), {
    verifiedActionsPermission: false,
  }), /permission/);
  await assert.rejects(reserve(ticket, null), /store/);
  await assert.rejects(reserve(ticket, new FakeAtomicStore(), {
    signingSecret: secret + "wrong",
  }), /signature/);
});

test("atomic quota adapter must reject replay, concurrency, daily overuse", async () => {
  const store = new FakeAtomicStore();
  const t1 = await issue();
  await reserve(t1, store);
  await assert.rejects(reserve(t1, store), /Replay/);
  const t2 = await issue();
  await assert.rejects(reserve(t2, store), /capacity/);
  store.finish(owner.githubUserId);
  await reserve(t2, store);
  store.finish(owner.githubUserId);
  const t3 = await issue();
  await reserve(t3, store);
  store.finish(owner.githubUserId);
  const t4 = await issue();
  await assert.rejects(reserve(t4, store), /quota/);
});

test("ticket grants no standalone dispatch and is never an access token", async () => {
  const ticket = await issue();
  assert.equal(ticket.split(".").length, 2);
  const decision = await reserve(ticket);
  assert.deepEqual(Object.keys(decision).sort(), [
    "fingerprint", "inputs", "ownerId", "ref", "repository", "state", "workflow",
  ]);
  assert.ok(!("githubAccessToken" in decision));
  assert.ok(!("dispatch" in decision));
  assert.ok(!("runId" in decision));
});
