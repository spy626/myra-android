/**
 * Slice 13: staging-only, fail-closed SQLite Durable Object rate gate.
 * A SINGLE global instance bounds even clients that rotate source addresses.
 * No route, OAuth identity, or GitHub credential is accepted from the phone.
 */
const PREFIX="video:oauth-rate:v1:";
const WINDOW_SECONDS=600;
const BUDGET=Object.freeze({
  start:Object.freeze({perClient:4,global:30}),
  callback:Object.freeze({perClient:8,global:60}),
});
const SOURCE=/^[0-9a-f]{64}$/;
const TIME_MIN=1_000_000_000;
function checkTime(nowSeconds) {
  if (!Number.isSafeInteger(nowSeconds) || nowSeconds<TIME_MIN)
    throw Error("Trusted server time required");
}
function checked(route,sourceKey,nowSeconds) {
  if (!Object.hasOwn(BUDGET,route) || typeof sourceKey!=="string" ||
      !SOURCE.test(sourceKey)) throw Error("Invalid rate gate request");
  checkTime(nowSeconds);
  return Math.floor(nowSeconds/WINDOW_SECONDS);
}
function count(value,max) {
  if (value===undefined) return 0;
  if (!Number.isSafeInteger(value) || value<0 || value>max)
    throw Error("Invalid durable rate counter");
  return value;
}
export class VideoOAuthRateGate {
  constructor(ctx) {
    const s=ctx?.storage;
    if (!s || typeof s.transaction!=="function" ||
        typeof s.getAlarm!=="function" || typeof s.setAlarm!=="function" ||
        typeof s.list!=="function" || typeof s.delete!=="function")
      throw Error("Durable SQLite limiter storage unavailable");
    this.storage=s;
  }
  async reserveAttempt({route,sourceKey,nowSeconds}) {
    const bucket=checked(route,sourceKey,nowSeconds);
    const limit=BUDGET[route];
    const globalKey=PREFIX+"global:"+route+":"+bucket;
    const clientKey=PREFIX+"client:"+route+":"+bucket+":"+sourceKey;
    const allowed=await this.storage.transaction(async tx=>{
      const global=count(await tx.get(globalKey),limit.global);
      const client=count(await tx.get(clientKey),limit.perClient);
      if (global>=limit.global || client>=limit.perClient) return false;
      await tx.put(globalKey,global+1);
      await tx.put(clientKey,client+1);
      return true;
    });
    // Fail closed if alarms cannot be installed; the consumed quota is never
    // refunded just because cleanup scheduling failed.
    if (allowed) {
      const alarm=await this.storage.getAlarm();
      if (alarm===null || alarm===undefined || alarm<=nowSeconds*1000) {
        await this.storage.setAlarm((bucket+2)*WINDOW_SECONDS*1000);
      }
    }
    return allowed;
  }
  // Called exclusively by the Cloudflare Durable Object alarm handler.
  async cleanupExpired(nowSeconds) {
    checkTime(nowSeconds);
    const current=Math.floor(nowSeconds/WINDOW_SECONDS);
    const rows=await this.storage.list({prefix:PREFIX});
    if (!(rows instanceof Map)) throw Error("Durable limiter enumeration unavailable");
    let deleted=0;
    for (const key of rows.keys()) {
      // Unknown or malformed keys remain untouched; no destructive deleteAll.
      const match=/^video:oauth-rate:v1:(?:global|client):(start|callback):(\d+)(?::[0-9a-f]{64})?$/.exec(key);
      if (!match) continue;
      const bucket=Number(match[2]);
      if (Number.isSafeInteger(bucket) && bucket<current-1) {
        await this.storage.delete(key);
        deleted++;
      }
    }
    return deleted;
  }
}
