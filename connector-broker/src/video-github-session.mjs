/**
 * Slice 10: server-only read-only verification of an already-created GitHub
 * OAuth session. Does NOT implement OAuth login, exchange, public routes or
 * Actions:write. The GitHub token is stored server-side, never in LYRA APK.
 */
const SESSION = /^[A-Za-z0-9_-]{32,128}$/;
const GITHUB_USER_API = 'https://api.github.com/user';
const MAX_BODY_BYTES = 16384;

async function readBoundedUtf8(response) {
  if (!response.body || typeof response.body.getReader !== 'function') {
    throw new Error('GitHub identity response is missing');
  }
  const advertised = response.headers?.get?.('content-length');
  if (advertised !== null && advertised !== undefined &&
      Number(advertised) > MAX_BODY_BYTES) {
    throw new Error('GitHub identity response too large');
  }
  const reader = response.body.getReader();
  const parts = [];
  let total = 0;
  try {
    while (true) {
      const {done, value} = await reader.read();
      if (done) break;
      total += value.length;
      if (total > MAX_BODY_BYTES) throw new Error('GitHub identity response too large');
      parts.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const all = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) { all.set(part, offset); offset += part.length; }
  return new TextDecoder('utf-8', {fatal: true}).decode(all);
}

/**
 * @param {object} params
 * params.sessionId is an opaque handle to the SERVER session vault, not a
 * GitHub token and not an asserted GitHub user ID.
 * vault.load(sessionId) must return server-stored session, or null.
 * fetchImpl is injected only by trusted server code (normal global fetch).
 */
export async function verifyGithubOwnerFromServerSession({
  sessionId, vault, fetchImpl, nowSeconds,
}) {
  if (typeof sessionId !== 'string' || !SESSION.test(sessionId)) {
    throw new Error('Invalid opaque server session handle');
  }
  if (!Number.isSafeInteger(nowSeconds) || nowSeconds < 1000000000 ||
      !vault || typeof vault.load !== 'function' ||
      typeof fetchImpl !== 'function') {
    throw new Error('Trusted server session verifier is unavailable');
  }
  const session = await vault.load(sessionId);
  if (!session || typeof session !== 'object' ||
      session.kind !== 'server-issued-github-oauth' ||
      session.id !== sessionId ||
      typeof session.accessToken !== 'string' ||
      !/^[A-Za-z0-9_-]{20,300}$/.test(session.accessToken) ||
      !Number.isSafeInteger(session.expiresAt) ||
      session.expiresAt <= nowSeconds ||
      !Number.isSafeInteger(session.githubUserId) || session.githubUserId < 1) {
    throw new Error('Server OAuth session unavailable or expired');
  }
  // Recheck GitHub on EACH approval; never trust an ID submitted by the phone.
  const response = await fetchImpl(GITHUB_USER_API, {
    method: 'GET', redirect: 'manual',
    headers: {
      'Authorization': 'Bearer ' + session.accessToken,
      'Accept': 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'LYRA-VideoStudio-OwnerVerification/1',
    },
  });
  if (response.status !== 200 || response.redirected ||
      !response.headers?.get?.('content-type')?.toLowerCase().includes('application/json')) {
    throw new Error('GitHub session could not be independently verified');
  }
  const data = JSON.parse(await readBoundedUtf8(response));
  if (!data || !Number.isSafeInteger(data.id) || data.id < 1 ||
      data.id !== session.githubUserId ||
      typeof data.login !== 'string' || !/^[A-Za-z0-9-]{1,100}$/.test(data.login)) {
    throw new Error('GitHub verified user does not match server session owner');
  }
  // Never return the token or user profile. Non-sensitive principal only.
  return Object.freeze({kind: 'verified-github-oauth', githubUserId: data.id,
    sessionId});
}