/** Slice 10: server-only Cloudflare Durable Object transactional reservation store.
 * No network route or GitHub dispatch. One DO instance per VERIFIED GitHub owner.
 * Input expiry does NOT auto-release active work; a future trusted GitHub
 * terminal reconciler is required to avoid overlapping remote renders.
 */
const NONCE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SESSION = /^[A-Za-z0-9_-]{16,128}$/;
const PROJECT = /^[A-Za-z0-9_-]{1,80}$/;
const OWNER_KEY = 'video:v1:owner', ACTIVE_KEY = 'video:v1:active';
const DAY_KEY = 'video:v1:day:', NONCE_KEY = 'video:v1:nonce:';

function checkedOwner(ownerId) {
  if (!Number.isSafeInteger(ownerId) || ownerId < 1) throw Error('Verified numeric owner required');
  return ownerId;
}
function day(nowSeconds) {
  if (!Number.isSafeInteger(nowSeconds) || nowSeconds < 1000000000)
    throw Error('Trusted server time required');
  return new Date(nowSeconds * 1000).toISOString().slice(0,10);
}
export function validateReservation(request, expectedOwner) {
  const keys=['ownerId','sessionId','nonce','projectId','nowSeconds','expiresAt','maxActive','maxPerUtcDay'];
  if (!request || typeof request!=='object' || Array.isArray(request) ||
      Object.keys(request).length!==keys.length || !keys.every(k=>Object.hasOwn(request,k)))
    throw Error('Unexpected reservation fields');
  checkedOwner(request.ownerId);
  const d=day(request.nowSeconds);
  if (request.ownerId!==expectedOwner || typeof request.nonce!=='string' || !NONCE.test(request.nonce) ||
      typeof request.sessionId!=='string' || !SESSION.test(request.sessionId) ||
      typeof request.projectId!=='string' || !PROJECT.test(request.projectId) ||
      request.maxActive!==1 || request.maxPerUtcDay!==3 ||
      !Number.isSafeInteger(request.expiresAt) || request.expiresAt<=request.nowSeconds ||
      request.expiresAt-request.nowSeconds>330)
    throw Error('Invalid owner-scoped reservation');
  return {...request,day:d};
}
export class VideoOwnerLedger {
  constructor(storage,ownerId) {
    checkedOwner(ownerId);
    if (!storage || typeof storage.transaction!=='function') throw Error('Durable transactional storage required');
    this.storage=storage;
    this.ownerId=ownerId;
  }
  async reserve(request) {
    const item=validateReservation(request,this.ownerId);
    return this.storage.transaction(async tx=>{
      const pinned=await tx.get(OWNER_KEY);
      if (pinned!==undefined && pinned!==this.ownerId) throw Error('Durable owner scope mismatch');
      if ((await tx.get(NONCE_KEY+item.nonce))!==undefined) return false;
      if ((await tx.get(ACTIVE_KEY))!==undefined) return false;
      const daily=DAY_KEY+item.day;
      const count=(await tx.get(daily))??0;
      if (!Number.isInteger(count) || count<0 || count>=3) return false;
      if (pinned===undefined) await tx.put(OWNER_KEY,this.ownerId);
      await tx.put(NONCE_KEY+item.nonce,{ownerId:item.ownerId,reservedAt:item.nowSeconds,day:item.day});
      await tx.put(daily,count+1);
      await tx.put(ACTIVE_KEY,{ownerId:item.ownerId,sessionId:item.sessionId,
        nonce:item.nonce,projectId:item.projectId,reservedAt:item.nowSeconds,
        state:'RESERVED_NOT_DISPATCHED'});
      return true;
    });
  }
  async stateForVerifiedOwner(ownerId,nowSeconds) {
    if (checkedOwner(ownerId)!==this.ownerId) throw Error('Cannot inspect another render owner');
    const d=day(nowSeconds);
    return this.storage.transaction(async tx=>{
      const pinned=await tx.get(OWNER_KEY);
      if (pinned!==undefined && pinned!==ownerId) throw Error('Durable owner scope mismatch');
      const active=(await tx.get(ACTIVE_KEY))??null;
      const dailyUsed=(await tx.get(DAY_KEY+d))??0;
      return Object.freeze({ownerId,active:active?{...active}:null,dailyUsed,dailyLimit:3});
    });
  }
  // No public completion/release: a ticket's expiry does not prove a render ended.
}