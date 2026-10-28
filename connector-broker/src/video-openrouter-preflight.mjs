/**
 * LYRA Video Studio Slice32: strictly read-only OpenRouter video availability.
 *
 * This module has NO POST code, NO video generation, NO provider fallbacks, and
 * NO access to Android credentials. The supplied API key must already live in
 * a trusted server's secret store; it is never returned or logged.
 *
 * Only these fixed HTTPS GET endpoints are ever requested:
 *   https://openrouter.ai/api/v1/key
 *   https://openrouter.ai/api/v1/videos/models
 *
 * A successful catalogue GET verifies metadata, NOT video generation success
 * or a provider-enforced zero-spend guarantee.
 */
import {FREE_WAN_MODEL_IDS, inspectFreeWanCatalogue} from "./video-openrouter-free.mjs";

const ROOT = "https://openrouter.ai/api/v1";
const MAX_BODY_LENGTH = 500_000;
const MAX_CATALOGUE_SIZE = 3000;
const DURATION = 5;
const ASPECT = "9:16";
const SAFE_RESOLUTIONS = ["480p", "720p"];
const ENABLED_SKU_KEYS = new Set(["generate", "generate_audio"]);

function emptyModels(status="NOT_CHECKED") {
  return FREE_WAN_MODEL_IDS.map(model=>({
    model, status, resolution:null,
    // No priced value, provider URL or foreign model data ever escapes.
  }));
}
function result(status, overrides={}) {
  return Object.freeze({
    status,
    authenticated:false,
    request_methods:["GET"],
    video_submission_allowed:false,
    videos_generated:0,
    paid_requests_made:0,
    models:emptyModels(),
    ...overrides,
  });
}
function validKey(key) {
  return typeof key==="string" && key.length>=15 && key.length<=1000 &&
    !/[\s\u0000-\u001f\u007f]/.test(key);
}
function zero(value) {
  return (typeof value==="number" && Number.isFinite(value) && value===0) ||
    (typeof value==="string" && /^0(?:\.0{1,12})?$/.test(value));
}
/** Fail closed if any SKU or parameter is omitted/unknown. */
function categorizeCandidate(entry, duplicates) {
  if (duplicates!==1) return {status: duplicates===0 ? "NOT_LISTED" : "DUPLICATE_ID",resolution:null};
  if (!entry || typeof entry!=="object" || Array.isArray(entry)) {
    return {status:"INVALID_METADATA",resolution:null};
  }
  const p=entry.pricing_skus;
  if (!p || typeof p!=="object" || Array.isArray(p)) {
    return {status:"PRICE_UNVERIFIED",resolution:null};
  }
  const skus=Object.entries(p);
  if (skus.length===0 || skus.length>30 ||
      !skus.some(([name])=>name==="generate" || name.startsWith("generate_")) ||
      !skus.every(([,value])=>zero(value))) {
    return {status:"PRICE_UNVERIFIED",resolution:null};
  }
  if (!Array.isArray(entry.supported_durations) ||
      !entry.supported_durations.includes(DURATION) ||
      !Array.isArray(entry.supported_aspect_ratios) ||
      !entry.supported_aspect_ratios.includes(ASPECT) ||
      !Array.isArray(entry.supported_resolutions)) {
    return {status:"SETTINGS_UNSUPPORTED",resolution:null};
  }
  const resolution=SAFE_RESOLUTIONS.find(v=>entry.supported_resolutions.includes(v));
  if(!resolution) return {status:"SETTINGS_UNSUPPORTED",resolution:null};
  return {status:"CATALOGUE_ZERO_PRICE_AND_SUPPORTED",resolution};
}
function inspectPublicMetadata(data) {
  if (!data || typeof data!=="object" || !Array.isArray(data.data) ||
      data.data.length>MAX_CATALOGUE_SIZE) throw Error("catalogue invalid");
  // Defense-in-depth: the same conservative shared Slice31 validator must
  // accept every metadata model that this read-only report marks supported.
  const shared=inspectFreeWanCatalogue(data);
  return FREE_WAN_MODEL_IDS.map(model=>{
    const exact=data.data.filter(x=>x?.id===model);
    let state=categorizeCandidate(exact[0],exact.length);
    if (state.status==="CATALOGUE_ZERO_PRICE_AND_SUPPORTED" &&
        !shared.some(x=>x.model===model && x.resolution===state.resolution)) {
      state={status:"PRICE_UNVERIFIED",resolution:null};
    }
    return {model, ...state};
  });
}
async function fixedGet(url, key, fetchImpl) {
  const allowed=new Set([ROOT+"/key",ROOT+"/videos/models"]);
  if (!allowed.has(url)) throw Error("internal route mismatch");
  const response=await fetchImpl(url,{
    method:"GET",
    headers:{
      Authorization:"Bearer "+key,
      Accept:"application/json",
    },
    redirect:"manual",
    signal:AbortSignal.timeout(15000),
  });
  if (!response || response.status!==200) throw Error("unavailable");
  // Read no opaque/untrusted provider errors, no redirects.
  const raw=await response.text();
  if (raw.length<2 || raw.length>MAX_BODY_LENGTH) throw Error("invalid size");
  return JSON.parse(raw);
}
/**
 * Read-only account-specific preflight.
 * No args other than a secret API key and injectable fetch for offline tests.
 * Unknown errors become a non-sensitive, non-actionable status.
 */
export async function readonlyWanVideoPreflight({apiKey, fetchImpl=fetch}={}) {
  if (!validKey(apiKey)) return result("SERVER_KEY_NOT_CONFIGURED");
  let keyResponse;
  try {
    keyResponse=await fixedGet(ROOT+"/key", apiKey, fetchImpl);
  } catch {
    return result("KEY_AUTH_UNVERIFIED");
  }
  if (!keyResponse || typeof keyResponse!=="object" ||
      !keyResponse.data || typeof keyResponse.data!=="object" ||
      typeof keyResponse.data.is_free_tier!=="boolean" ||
      keyResponse.data.is_management_key===true) {
    return result("KEY_AUTH_UNVERIFIED");
  }
  let catalogue;
  try {
    catalogue=await fixedGet(ROOT+"/videos/models",apiKey,fetchImpl);
  } catch {
    return result("CATALOGUE_UNAVAILABLE",{
      authenticated:true,
      free_tier_key:keyResponse.data.is_free_tier,
    });
  }
  try {
    return result("AUTHENTICATED_CATALOGUE_CHECKED",{
      authenticated:true,
      free_tier_key:keyResponse.data.is_free_tier,
      models:inspectPublicMetadata(catalogue),
    });
  } catch {
    return result("CATALOGUE_INVALID",{
      authenticated:true,
      free_tier_key:keyResponse.data.is_free_tier,
    });
  }
}
