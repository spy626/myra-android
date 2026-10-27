/**
 * Slice31 — OpenRouter Wan VIDEO, fail-closed free-only adapter.
 *
 * Isolated from deployed connector-broker/src/index.js and Android APK.
 * No new production route, no credential persistence, no default POST.
 * This file can be called only by a future separately authorized server.
 * The explicit operatorApproved flag is a kill switch, NOT authentication.
 * Auth/session binding, durable per-owner quota and zero-spend key policy
 * must be deployed and separately verified before live activation.
 *
 * OpenRouter video API (not /chat/completions):
 * GET /api/v1/videos/models, POST /api/v1/videos,
 * GET /api/v1/videos/:id, GET /api/v1/videos/:id/content.
 */
const API = "https://openrouter.ai/api/v1/videos";
const MODELS = Object.freeze([
  "alibaba/wan-2.7:free",
  "alibaba/wan-2.6:free",
]);
const NO_REDIRECT = "manual";
const MAX_CATALOGUE_MODELS = 3000;
const MAX_JSON_SIZE = 500_000;
const MAX_PROMPT_LENGTH = 600;
const DURATION_SECONDS = 5;
const ASPECT_RATIO = "9:16";
const RESOLUTIONS = Object.freeze(["480p", "720p"]);

function requireExactRecord(raw, fields, label) {
  if (raw === null || typeof raw !== "object" || Array.isArray(raw) ||
      Object.getPrototypeOf(raw) !== Object.prototype ||
      Object.keys(raw).length !== fields.length ||
      !fields.every(k => Object.prototype.hasOwnProperty.call(raw, k))) {
    throw new Error("Invalid " + label);
  }
}
function fixedId(value, label) {
  if (typeof value !== "string" ||
      !/^[A-Za-z0-9_-]{1,128}$/.test(value)) {
    throw new Error("Invalid " + label);
  }
  return value;
}
function requiredKey(apiKey) {
  if (typeof apiKey !== "string" ||
      apiKey.length < 15 || apiKey.length > 1000 ||
      /\s/.test(apiKey)) {
    throw new Error("Server-side OpenRouter API key unavailable");
  }
  return apiKey;
}
function requireModel(model) {
  if (typeof model !== "string" || !MODELS.includes(model)) {
    throw new Error("Only the two exact free Wan video models are approved");
  }
  return model;
}
function requestConfig(input) {
  requireExactRecord(input, ["model", "prompt"], "free video request");
  const model = requireModel(input.model);
  if (typeof input.prompt !== "string" ||
      input.prompt !== input.prompt.trim() ||
      input.prompt.length < 8 ||
      input.prompt.length > MAX_PROMPT_LENGTH ||
      /[\u0000-\u001f\u007f]/.test(input.prompt)) {
    throw new Error("Invalid short text-to-video prompt");
  }
  return { model, prompt: input.prompt };
}
function strictlyZero(value) {
  return (typeof value === "number" && Number.isFinite(value) && value === 0) ||
    (typeof value === "string" && /^0(?:\.0{1,12})?$/.test(value));
}
function zeroPricedVideoModel(model) {
  if (model === null || typeof model !== "object" || Array.isArray(model) ||
      !MODELS.includes(model.id)) return false;
  const skus = model.pricing_skus;
  if (skus === null || typeof skus !== "object" || Array.isArray(skus)) return false;
  const entries = Object.entries(skus);
  // Missing or unrecognized per-SKU price is *not* proof of free video.
  return entries.length > 0 && entries.length < 30 &&
    entries.some(([key]) => key === "generate" || key.startsWith("generate_")) &&
    entries.every(([, value]) => strictlyZero(value));
}
function chooseResolution(model) {
  if (!Array.isArray(model.supported_durations) ||
      !model.supported_durations.includes(DURATION_SECONDS) ||
      !Array.isArray(model.supported_aspect_ratios) ||
      !model.supported_aspect_ratios.includes(ASPECT_RATIO) ||
      !Array.isArray(model.supported_resolutions)) {
    throw new Error("Free video model does not support approved clip settings");
  }
  const selected = RESOLUTIONS.find(r => model.supported_resolutions.includes(r));
  if (!selected) throw new Error("Approved low-cost video resolution unavailable");
  return selected;
}
export function inspectFreeWanCatalogue(payload) {
  if (payload === null || typeof payload !== "object" ||
      !Array.isArray(payload.data) ||
      payload.data.length > MAX_CATALOGUE_MODELS) {
    throw new Error("Malformed OpenRouter video model catalogue");
  }
  const found = [];
  for (const id of MODELS) {
    const matching = payload.data.filter(x => x && x.id === id);
    // Duplicate identities or completely missing models are not trusted.
    if (matching.length !== 1 || !zeroPricedVideoModel(matching[0])) continue;
    try {
      const resolution = chooseResolution(matching[0]);
      found.push(Object.freeze({
        model: id,
        duration: DURATION_SECONDS,
        aspect_ratio: ASPECT_RATIO,
        resolution,
        catalog_price_verified_zero: true,
      }));
    } catch { /* Unsupported parameters => no submission. */ }
  }
  return Object.freeze(found);
}
function safeHeaders(apiKey) {
  return { "Authorization": "Bearer " + requiredKey(apiKey),
    "Accept": "application/json",
    "Content-Type": "application/json" };
}
async function safeJsonResponse(response, label, expectedStatus) {
  if (!response || !Number.isInteger(response.status) ||
      response.status !== expectedStatus) {
    // Never echo provider body (may contain secrets or user prompts).
    throw new Error(label + " unavailable or rejected");
  }
  const raw = await response.text();
  if (raw.length < 2 || raw.length > MAX_JSON_SIZE) {
    throw new Error(label + " response size rejected");
  }
  let data;
  try { data = JSON.parse(raw); }
  catch { throw new Error(label + " response is not valid JSON"); }
  return data;
}
/**
 * Read-only model discovery. This is NOT evidence of account-specific
 * availability unless called with the account's server-held key.
 */
export async function checkLiveFreeWanAvailability({apiKey, fetchImpl = fetch}) {
  const response = await fetchImpl(API + "/models", {
    method: "GET", headers: safeHeaders(apiKey), redirect: NO_REDIRECT,
    signal: AbortSignal.timeout(20000),
  });
  const payload = await safeJsonResponse(response, "Video catalogue", 200);
  return inspectFreeWanCatalogue(payload);
}
export function approvedFreeWanPayload(input, allowed) {
  const {model, prompt} = requestConfig(input);
  if (!Array.isArray(allowed)) throw new Error("Live free model proof required");
  const exact = allowed.filter(x =>
    x && x.model === model && x.catalog_price_verified_zero === true &&
    x.duration === DURATION_SECONDS &&
    x.aspect_ratio === ASPECT_RATIO &&
    RESOLUTIONS.includes(x.resolution));
  if (exact.length !== 1) throw new Error("Model missing from live free video catalogue");
  return Object.freeze({
    model, prompt,
    duration: DURATION_SECONDS,
    resolution: exact[0].resolution,
    aspect_ratio: ASPECT_RATIO,
    generate_audio: false,
  });
}
/**
 * Network POST is opt-in and private to the future server integration.
 * The method re-fetches CURRENT catalogue immediately before submitting.
 * An explicit server-side owner session, atomic quota, and $0 account cap
 * must be established by the caller. Neither a user header nor request body
 * is an acceptable source of authorization.
 */
export async function submitFreeWanAfterServerApproval({
  apiKey, input, operatorApproved = false,
  zeroSpendAccountConfirmed = false, fetchImpl = fetch,
}) {
  if (operatorApproved !== true || zeroSpendAccountConfirmed !== true) {
    throw new Error("External video network submission disabled by default");
  }
  requestConfig(input); // Reject paid IDs before any network request.
  const live = await checkLiveFreeWanAvailability({apiKey, fetchImpl});
  const body = approvedFreeWanPayload(input, live);
  const response = await fetchImpl(API, {
    method: "POST", headers: safeHeaders(apiKey),
    redirect: NO_REDIRECT,
    signal: AbortSignal.timeout(30000),
    body: JSON.stringify(body),
  });
  const result = await safeJsonResponse(response, "Video submit", 202);
  const id = fixedId(result?.id, "OpenRouter job ID");
  if (!["pending", "queued", "processing", "running"].includes(result.status)) {
    throw new Error("Video submit returned unexpected non-pending state");
  }
  const expected = "/api/v1/videos/" + id;
  if (result.polling_url !== expected &&
      result.polling_url !== "https://openrouter.ai" + expected) {
    throw new Error("Untrusted video polling URL");
  }
  return Object.freeze({
    id, model: body.model, status: result.status,
    next: "POLL_AUTHENTICATED_STATUS",
    charged_zero_not_yet_verified: true,
  });
}
/** Read-only status check; never follows provider-provided polling URLs. */
export async function pollFreeWanJob({apiKey, jobId, fetchImpl = fetch}) {
  const id = fixedId(jobId, "job ID");
  const result = await safeJsonResponse(await fetchImpl(API + "/" + id, {
    method: "GET", headers: safeHeaders(apiKey),
    redirect: NO_REDIRECT, signal: AbortSignal.timeout(20000),
  }), "Video job", 200);
  if (result.id !== id ||
      !["pending", "queued", "processing", "running", "completed",
        "failed", "cancelled", "expired"].includes(result.status)) {
    throw new Error("Invalid video job response");
  }
  const cost = result.usage?.cost;
  // Missing usage is unknown, NEVER proof of free billing.
  const provenZeroCost = strictlyZero(cost);
  return Object.freeze({
    id,
    status: result.status,
    billing: provenZeroCost ? "ZERO_REPORTED" :
      (typeof cost === "number" && cost > 0 ? "UNEXPECTED_CHARGE" : "UNKNOWN"),
    content_endpoint: result.status === "completed" && provenZeroCost
      ? API + "/" + id + "/content?index=0" : null,
    // MP4 identity/content must be independently validated after authenticated download.
    mp4_validated: false,
  });
}
export const FREE_WAN_MODEL_IDS = MODELS;
