/**
 * LYRA Video Studio slice 9 — isolated SERVER-SIDE authorization policy.
 *
 * NOT routed into the current Cloudflare broker. No workflow dispatch is
 * performed here. A future backend must supply its independently verified
 * GitHub-user identity, deployment/permission checks and an ATOMIC durable
 * quota+nonce store; never trust a principal or these checks from the APK.
 *
 * Approval tickets are HMAC-authenticated short-lived claims, NOT GitHub
 * access tokens. The signing secret stays only on the future server.
 */
const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });
const OWNER_REPO = "spy626/myra-android";
const WORKFLOW = "video-studio-slice-6.yml";
const REF = "agent/lyra-video-studio-slice-6";
const VALID_KEYS = ["project_id", "floors", "color", "roof", "garden", "target"];
const VALID_ISSUER = "lyra-video-render-gate-v1";
const TTL_SECONDS = 300;
const MAX_TICKET_BYTES = 4096;

function strictRecord(value, keys, label) {
  if (!value || typeof value !== "object" || Array.isArray(value) ||
      Object.keys(value).length !== keys.length ||
      !keys.every(k => Object.prototype.hasOwnProperty.call(value, k))) {
    throw new Error(label + " has unexpected fields");
  }
}

export function approvedRenderInputs(raw) {
  strictRecord(raw, VALID_KEYS, "Video render inputs");
  if (typeof raw.project_id !== "string" ||
      !/^[A-Za-z0-9_-]{1,80}$/.test(raw.project_id) ||
      !["1", "2"].includes(raw.floors) ||
      !["cream", "blue", "terracotta"].includes(raw.color) ||
      !["flat", "gable"].includes(raw.roof) ||
      !["yes", "no"].includes(raw.garden) ||
      !["30", "60"].includes(raw.target)) {
    throw new Error("Unsupported 6-second video test inputs");
  }
  // This is the exact allowlist from the Kotlin and Python Slice 8 contracts.
  return Object.fromEntries(VALID_KEYS.map(k => [k, raw[k]]));
}

function canonicalInputs(inputs) {
  const data = approvedRenderInputs(inputs);
  return "lyra-video-render-manual-v1\n" +
    VALID_KEYS.map(k => k + "=" + data[k] + "\n").join("");
}

function bytesToBase64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-")
    .replace(/\//g, "_").replace(/=+$/g, "");
}

function base64urlToBytes(value) {
  if (!/^[A-Za-z0-9_-]{1,4096}$/.test(value)) {
    throw new Error("Malformed approval ticket");
  }
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64 + "=".repeat((4 - base64.length % 4) % 4);
  return Uint8Array.from(atob(padded), c => c.charCodeAt(0));
}

function trustedPrincipal(principal) {
  // Trusted principal MUST be set by future server-side OAuth validation,
  // never copied from untrusted request JSON or paired-device assertions.
  strictRecord(principal, ["kind", "githubUserId", "sessionId"], "Server principal");
  if (principal.kind !== "verified-github-oauth" ||
      !Number.isSafeInteger(principal.githubUserId) ||
      principal.githubUserId < 1 ||
      typeof principal.sessionId !== "string" ||
      !/^[A-Za-z0-9_-]{16,128}$/.test(principal.sessionId)) {
    throw new Error("Verified GitHub user session required");
  }
  return principal;
}

function requireTime(value) {
  if (!Number.isSafeInteger(value) || value < 1_000_000_000) {
    throw new Error("Trusted server timestamp required");
  }
  return value;
}

async function hmacKey(secret) {
  // This is a server-side-only high-entropy secret, NOT the LYRA pairing key.
  if (typeof secret !== "string" || secret.length < 40 ||
      secret.length > 512) {
    throw new Error("Video approval signing secret missing");
  }
  return crypto.subtle.importKey("raw", encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

export async function approvedRenderFingerprint(inputs) {
  const digest = await crypto.subtle.digest("SHA-256",
    encoder.encode(canonicalInputs(inputs)));
  return [...new Uint8Array(digest)].map(x => x.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * Called only after a verified signed-in user EXPLICITLY confirms the exact
 * six safe inputs. It does not submit a job or grant GitHub repository access.
 */
export async function issueVideoRenderApproval({
  inputs, principal, explicitApproval, signingSecret, nowSeconds,
}) {
  if (explicitApproval !== true) {
    throw new Error("Explicit user approval required");
  }
  const owner = trustedPrincipal(principal);
  const approved = approvedRenderInputs(inputs);
  const issued = requireTime(nowSeconds);
  const key = await hmacKey(signingSecret);
  const payload = {
    issuer: VALID_ISSUER,
    repository: OWNER_REPO,
    workflow: WORKFLOW,
    ref: REF,
    ownerId: owner.githubUserId,
    sessionId: owner.sessionId,
    nonce: crypto.randomUUID(),
    issuedAt: issued,
    expiresAt: issued + TTL_SECONDS,
    inputs: approved,
    fingerprint: await approvedRenderFingerprint(approved),
  };
  const encoded = bytesToBase64url(encoder.encode(JSON.stringify(payload)));
  const signature = bytesToBase64url(new Uint8Array(
    await crypto.subtle.sign("HMAC", key, encoder.encode(encoded))
  ));
  return encoded + "." + signature;
}

/**
 * Authorization decision, BEFORE any hypothetical GitHub Actions dispatch.
 * durableReservationStore.reserve MUST atomically enforce single-use nonce,
 * one active render per owner and max 3 submissions / owner / UTC day.
 *
 * Platform checks must come from trusted server-side GitHub API calls:
 * - owner-authorized workflow installation on default branch,
 * - scoped Actions:write authorization for THIS repository.
 * Neither boolean may originate from user-supplied request JSON.
 */
export async function verifyAndReserveVideoRender({
  ticket, principal, signingSecret, nowSeconds, durableReservationStore,
  verifiedDeployment, verifiedActionsPermission,
}) {
  const actor = trustedPrincipal(principal);
  const now = requireTime(nowSeconds);
  if (verifiedDeployment !== true || verifiedActionsPermission !== true) {
    throw new Error("Approved workflow deployment and scoped Actions permission required");
  }
  if (typeof ticket !== "string" || ticket.length > MAX_TICKET_BYTES ||
      ticket.split(".").length !== 2) {
    throw new Error("Invalid approval ticket");
  }
  if (!durableReservationStore ||
      typeof durableReservationStore.reserve !== "function") {
    throw new Error("Atomic durable reservation store unavailable");
  }
  const [dataPart, signaturePart] = ticket.split(".");
  const key = await hmacKey(signingSecret);
  const valid = await crypto.subtle.verify("HMAC", key,
    base64urlToBytes(signaturePart), encoder.encode(dataPart));
  if (!valid) throw new Error("Approval signature rejected");
  const payload = JSON.parse(decoder.decode(base64urlToBytes(dataPart)));
  strictRecord(payload, ["issuer", "repository", "workflow", "ref", "ownerId",
    "sessionId", "nonce", "issuedAt", "expiresAt", "inputs", "fingerprint"],
    "Approval claims");
  if (payload.issuer !== VALID_ISSUER ||
      payload.repository !== OWNER_REPO ||
      payload.workflow !== WORKFLOW ||
      payload.ref !== REF) {
    throw new Error("Video workflow binding mismatch");
  }
  if (payload.ownerId !== actor.githubUserId ||
      payload.sessionId !== actor.sessionId) {
    throw new Error("Render approval belongs to another authenticated session");
  }
  if (typeof payload.nonce !== "string" ||
      !/^[0-9a-f-]{36}$/.test(payload.nonce) ||
      !Number.isSafeInteger(payload.issuedAt) ||
      !Number.isSafeInteger(payload.expiresAt) ||
      payload.expiresAt !== payload.issuedAt + TTL_SECONDS ||
      payload.issuedAt > now + 30 ||
      payload.expiresAt <= now) {
    throw new Error("Expired or invalid approval lifetime");
  }
  const inputs = approvedRenderInputs(payload.inputs);
  if (payload.fingerprint !== await approvedRenderFingerprint(inputs)) {
    throw new Error("Approved video fields changed");
  }
  // Atomic reserve must be backed by a real transactional store, not an
  // in-memory Map shared across disposable Cloudflare Worker isolates.
  const reserved = await durableReservationStore.reserve({
    ownerId: actor.githubUserId,
    sessionId: actor.sessionId,
    nonce: payload.nonce,
    projectId: inputs.project_id,
    nowSeconds: now,
    expiresAt: payload.expiresAt,
    maxActive: 1,
    maxPerUtcDay: 3,
  });
  if (reserved !== true) {
    throw new Error("Replay, duplicate, capacity or daily quota exceeded");
  }
  return Object.freeze({
    ownerId: actor.githubUserId,
    fingerprint: payload.fingerprint,
    inputs,
    repository: OWNER_REPO,
    workflow: WORKFLOW,
    ref: REF,
    // Authorized locally, not submitted to GitHub.
    state: "RESERVED_NOT_DISPATCHED",
  });
}
