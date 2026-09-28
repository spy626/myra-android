const textEncoder = new TextEncoder();
const EXPECTED_TOKEN_SECONDS_MAX = 3700;
const MAX_WRITE_FILES = 12;
const MAX_FILE_BYTES = 220_000;
const MAX_TOTAL_WRITE_BYTES = 650_000;
let cachedPrivateKeyPem = null;
let cachedPrivateKey = null;

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      ...headers,
    },
  });
}

function requireEnv(env, name) {
  const value = String(env[name] || "").trim();
  if (!value) throw new Error("Connector broker is not configured: " + name);
  return value;
}

function requireIntegerEnv(env, name) {
  const raw = requireEnv(env, name);
  if (!/^[1-9][0-9]{0,19}$/.test(raw)) {
    throw new Error("Connector broker has invalid " + name);
  }
  return raw;
}

function requirePairingSecret(value) {
  const clean = String(value || "").trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(clean)) {
    throw new Error("LYRA pairing key is invalid");
  }
  return clean;
}

function requireSha(value, label = "GitHub commit") {
  const clean = String(value || "").trim().toLowerCase();
  if (!/^[0-9a-f]{40,64}$/.test(clean)) {
    throw new Error(label + " SHA is invalid");
  }
  return clean;
}

function requireBranch(value, label) {
  const clean = String(value || "").trim();
  if (
    clean.length < 1 ||
    clean.length > 200 ||
    clean.startsWith("/") ||
    clean.endsWith("/") ||
    !/^[A-Za-z0-9._/-]+$/.test(clean) ||
    clean.split("/").some(part => !part || part === "." || part === "..")
  ) {
    throw new Error(label + " is invalid");
  }
  return clean;
}

function requireWritePath(value) {
  const clean = String(value || "").trim().replace(/^\/+/, "");
  if (!clean || clean.length > 1024 || clean.endsWith("/")) {
    throw new Error("GitHub write path is invalid");
  }
  const parts = clean.split("/");
  if (parts.some(part =>
    !part || part === "." || part === ".." || part.length > 255 || /[\u0000-\u001f\u007f]/.test(part)
  )) {
    throw new Error("GitHub write path contains an unsafe segment");
  }

  const lower = clean.toLowerCase();
  const base = parts[parts.length - 1].toLowerCase();
  if (
    parts.some(part => part.toLowerCase() === ".git") ||
    lower === ".github/workflows" ||
    lower.startsWith(".github/workflows/") ||
    base === ".env" ||
    base.startsWith(".env.") ||
    base === "id_rsa" ||
    base === "id_ed25519" ||
    /\.(pem|key|p12|pfx)$/i.test(base)
  ) {
    throw new Error("Sensitive or workflow paths are blocked by LYRA connector policy");
  }
  return clean;
}

function requireTextContent(value) {
  if (typeof value !== "string") throw new Error("GitHub write content must be text");
  const bytes = textEncoder.encode(value);
  if (bytes.length > MAX_FILE_BYTES) {
    throw new Error("GitHub write file exceeds the per-file size bound");
  }
  if (
    value.includes("\u0000") ||
    /-----BEGIN (?:RSA )?PRIVATE KEY-----/.test(value) ||
    /\bgithub_pat_[A-Za-z0-9_]{20,}\b/.test(value) ||
    /\bgh[oprsu]_[A-Za-z0-9]{20,}\b/.test(value)
  ) {
    throw new Error("Potential secret material is blocked by LYRA connector policy");
  }
  return { text: value, bytes: bytes.length };
}

function requireCommitMessage(value) {
  const clean = String(value || "").trim();
  if (!clean || clean.length > 180 || /[\u0000-\u001f\u007f]/.test(clean.replace(/\n/g, ""))) {
    throw new Error("GitHub commit message is invalid");
  }
  return clean;
}

function requirePullTitle(value) {
  const clean = String(value || "").trim();
  if (!clean || clean.length > 180 || /[\u0000-\u001f\u007f]/.test(clean)) {
    throw new Error("GitHub pull request title is invalid");
  }
  return clean;
}

function requirePullBody(value) {
  const clean = String(value || "").trim();
  if (clean.length > 12_000 || clean.includes("\u0000")) {
    throw new Error("GitHub pull request body is invalid");
  }
  return clean;
}

function base64Url(bytes) {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function base64UrlText(value) {
  return base64Url(textEncoder.encode(value));
}

async function pairingMatches(env, supplied) {
  const expected = requirePairingSecret(requireEnv(env, "LYRA_PAIRING_SECRET"));
  const candidate = requirePairingSecret(supplied);
  const payload = textEncoder.encode("lyra-github-pairing-v1");

  const expectedKey = await crypto.subtle.importKey(
    "raw",
    textEncoder.encode(expected),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const candidateKey = await crypto.subtle.importKey(
    "raw",
    textEncoder.encode(candidate),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", candidateKey, payload);
  return crypto.subtle.verify("HMAC", expectedKey, signature, payload);
}

async function requirePairing(env, value) {
  const pairing = requirePairingSecret(value);
  if (!await pairingMatches(env, pairing)) {
    const error = new Error("LYRA pairing key does not match the Cloudflare secret");
    error.status = 401;
    error.code = "pairing_rejected";
    throw error;
  }
  return pairing;
}

function pemBody(pem) {
  return String(pem || "")
    .replace(/-----BEGIN [^-]+-----/g, "")
    .replace(/-----END [^-]+-----/g, "")
    .replace(/\s+/g, "");
}

function decodeBase64(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, c => c.charCodeAt(0));
}

function derLength(length) {
  if (length < 0x80) return Uint8Array.of(length);
  const bytes = [];
  let value = length;
  while (value > 0) {
    bytes.unshift(value & 0xff);
    value >>>= 8;
  }
  return Uint8Array.of(0x80 | bytes.length, ...bytes);
}

function concatBytes(...parts) {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function wrapPkcs1AsPkcs8(pkcs1) {
  const version = Uint8Array.of(0x02, 0x01, 0x00);
  const rsaAlgorithm = Uint8Array.of(
    0x30, 0x0d,
    0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01,
    0x05, 0x00,
  );
  const octet = concatBytes(Uint8Array.of(0x04), derLength(pkcs1.length), pkcs1);
  const body = concatBytes(version, rsaAlgorithm, octet);
  return concatBytes(Uint8Array.of(0x30), derLength(body.length), body);
}

async function githubPrivateKey(env) {
  const pem = requireEnv(env, "GITHUB_APP_PRIVATE_KEY");
  if (cachedPrivateKey && cachedPrivateKeyPem === pem) return cachedPrivateKey;

  const isPkcs1 = pem.includes("BEGIN RSA PRIVATE KEY");
  const isPkcs8 = pem.includes("BEGIN PRIVATE KEY");
  if (!isPkcs1 && !isPkcs8) {
    throw new Error("GITHUB_APP_PRIVATE_KEY must be a GitHub App PEM private key");
  }
  const decoded = decodeBase64(pemBody(pem));
  const pkcs8 = isPkcs1 ? wrapPkcs1AsPkcs8(decoded) : decoded;
  cachedPrivateKey = await crypto.subtle.importKey(
    "pkcs8",
    pkcs8,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  cachedPrivateKeyPem = pem;
  return cachedPrivateKey;
}

async function githubAppJwt(env) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64UrlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const payload = base64UrlText(JSON.stringify({
    iat: now - 60,
    exp: now + 540,
    iss: requireIntegerEnv(env, "GITHUB_APP_ID"),
  }));
  const signingInput = header + "." + payload;
  const signature = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    await githubPrivateKey(env),
    textEncoder.encode(signingInput),
  );
  return signingInput + "." + base64Url(new Uint8Array(signature));
}

function githubHeaders(token) {
  return {
    "accept": "application/vnd.github+json",
    "authorization": "Bearer " + token,
    "x-github-api-version": "2022-11-28",
    "user-agent": "LYRA-GitHub-Connector/3",
  };
}

async function githubJson(url, token, label, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: {
      ...githubHeaders(token),
      ...(options.headers || {}),
    },
    redirect: "manual",
  });
  if (response.status >= 300 && response.status < 400) {
    throw new Error(label + " redirect was refused");
  }
  const responseText = await response.text();
  let data = {};
  if (responseText) {
    try {
      data = JSON.parse(responseText);
    } catch {
      throw new Error(label + " returned invalid JSON");
    }
  }
  if (!response.ok) {
    const detail = String(data?.message || "").trim();
    const error = new Error(
      label + " failed (HTTP " + response.status + ")" + (detail ? ": " + detail : "")
    );
    error.status = response.status;
    throw error;
  }
  return data;
}

async function readJsonBody(request, maxBytes) {
  if (request.method !== "POST") {
    const error = new Error("Only POST is allowed");
    error.status = 405;
    error.code = "method_not_allowed";
    throw error;
  }
  const type = String(request.headers.get("content-type") || "").toLowerCase();
  if (!type.includes("application/json")) {
    const error = new Error("application/json is required");
    error.status = 415;
    error.code = "content_type_required";
    throw error;
  }
  const buffer = await request.arrayBuffer();
  if (buffer.byteLength < 2 || buffer.byteLength > maxBytes) {
    const error = new Error("Request body is empty or too large");
    error.status = 413;
    error.code = "request_too_large";
    throw error;
  }
  try {
    return JSON.parse(new TextDecoder().decode(buffer));
  } catch {
    const error = new Error("Request JSON is invalid");
    error.status = 400;
    error.code = "invalid_json";
    throw error;
  }
}

function expectedBinding(env) {
  const repository = requireEnv(env, "GITHUB_REPOSITORY");
  const parts = repository.split("/");
  if (
    parts.length !== 2 ||
    !/^[A-Za-z0-9_.-]{1,100}$/.test(parts[0]) ||
    !/^[A-Za-z0-9_.-]{1,100}$/.test(parts[1])
  ) {
    throw new Error("GITHUB_REPOSITORY is invalid");
  }
  const branch = requireBranch(requireEnv(env, "GITHUB_BRANCH"), "GITHUB_BRANCH");
  if (["main", "master"].includes(branch.toLowerCase())) {
    throw new Error("GITHUB_BRANCH cannot be main/master");
  }
  const account = requireEnv(env, "GITHUB_ACCOUNT_LOGIN");
  if (!/^[A-Za-z0-9-]{1,100}$/.test(account)) {
    throw new Error("GITHUB_ACCOUNT_LOGIN is invalid");
  }
  const prBase = requireBranch(requireEnv(env, "GITHUB_PR_BASE"), "GITHUB_PR_BASE");
  return { repository, owner: parts[0], name: parts[1], branch, account, prBase };
}

function repoApi(binding, suffix) {
  return "https://api.github.com/repos/" +
    encodeURIComponent(binding.owner) + "/" + encodeURIComponent(binding.name) + suffix;
}

async function discoverInstallation(env, appJwt, binding) {
  const installations = await githubJson(
    "https://api.github.com/app/installations?per_page=100",
    appJwt,
    "GitHub App installation discovery",
  );
  if (!Array.isArray(installations)) {
    throw new Error("GitHub App installation discovery returned invalid data");
  }
  const matching = installations.filter(installation => {
    const login = String(installation?.account?.login || "").trim();
    return login.toLowerCase() === binding.account.toLowerCase();
  });
  if (matching.length === 0) {
    throw new Error("LYRA GitHub App is not installed on the configured account");
  }
  if (matching.length > 1) {
    throw new Error("Multiple LYRA GitHub App installations matched the configured account");
  }
  const installation = matching[0];
  const installationId = String(installation.id || "").trim();
  if (!/^[1-9][0-9]{0,19}$/.test(installationId)) {
    throw new Error("GitHub App installation id was invalid");
  }
  return {
    id: installationId,
    login: String(installation.account?.login || "").trim(),
  };
}

async function issueScopedToken(env, permissions, label) {
  const binding = expectedBinding(env);
  const appJwt = await githubAppJwt(env);
  const installation = await discoverInstallation(env, appJwt, binding);
  const grant = await githubJson(
    "https://api.github.com/app/installations/" + installation.id + "/access_tokens",
    appJwt,
    label,
    {
      method: "POST",
      headers: { "content-type": "application/json; charset=utf-8" },
      body: JSON.stringify({
        repositories: [binding.name],
        permissions,
      }),
    },
  );

  const token = String(grant.token || "").trim();
  if (token.length < 20 || token.length > 1024 || /\s/.test(token)) {
    throw new Error("GitHub installation token response was invalid");
  }
  const expiresAtMs = Date.parse(String(grant.expires_at || ""));
  const expiresIn = Math.floor((expiresAtMs - Date.now()) / 1000);
  if (!Number.isFinite(expiresIn) || expiresIn < 60 || expiresIn > EXPECTED_TOKEN_SECONDS_MAX) {
    throw new Error("GitHub installation token expiry was invalid");
  }
  return { binding, installation, token, expiresIn };
}

async function branchHead(binding, token, branch = binding.branch) {
  const data = await githubJson(
    repoApi(binding, "/git/ref/heads/" + encodeURIComponent(branch)),
    token,
    "GitHub branch head verification",
  );
  return requireSha(data?.object?.sha, "GitHub branch head");
}

async function verifyRepository(binding, token) {
  const repo = await githubJson(
    repoApi(binding, ""),
    token,
    "GitHub repository verification",
  );
  if (String(repo.full_name || "").toLowerCase() !== binding.repository.toLowerCase()) {
    throw new Error("GitHub repository identity did not match");
  }
  const branchData = await githubJson(
    repoApi(binding, "/branches/" + encodeURIComponent(binding.branch)),
    token,
    "GitHub branch verification",
  );
  if (String(branchData.name || "") !== binding.branch) {
    throw new Error("GitHub branch identity did not match");
  }
}

async function issueInstallationGrant(env) {
  const scoped = await issueScopedToken(
    env,
    { actions: "read", contents: "read" },
    "GitHub read installation token",
  );
  await verifyRepository(scoped.binding, scoped.token);
  return {
    access_token: scoped.token,
    expires_in: scoped.expiresIn,
    token_type: "bearer",
    login: scoped.installation.login,
    repository: scoped.binding.repository,
    branch: scoped.binding.branch,
  };
}

async function tokenEndpoint(request, env) {
  const body = await readJsonBody(request, 4096);
  await requirePairing(env, body.pairing_secret);
  return json(await issueInstallationGrant(env));
}

async function writeScoped(env) {
  return issueScopedToken(
    env,
    { actions: "read", contents: "write", pull_requests: "write" },
    "GitHub write installation token",
  );
}

async function writeCheckEndpoint(request, env) {
  const body = await readJsonBody(request, 4096);
  await requirePairing(env, body.pairing_secret);
  const scoped = await writeScoped(env);
  const head = await branchHead(scoped.binding, scoped.token);
  await branchHead(scoped.binding, scoped.token, scoped.binding.prBase);
  return json({
    enabled: true,
    repository: scoped.binding.repository,
    branch: scoped.binding.branch,
    head,
    pr_base: scoped.binding.prBase,
    write_model: "broker-gated",
  });
}

async function commitEndpoint(request, env) {
  const body = await readJsonBody(request, 800_000);
  await requirePairing(env, body.pairing_secret);

  const expectedHead = requireSha(body.expected_head, "Expected branch head");
  const message = requireCommitMessage(body.message);
  if (!Array.isArray(body.files) || body.files.length < 1 || body.files.length > MAX_WRITE_FILES) {
    throw new Error("GitHub write must contain 1.." + MAX_WRITE_FILES + " text files");
  }

  let total = 0;
  const seen = new Set();
  const files = body.files.map(item => {
    const path = requireWritePath(item?.path);
    if (seen.has(path.toLowerCase())) {
      throw new Error("GitHub write contains duplicate file paths");
    }
    seen.add(path.toLowerCase());
    const checked = requireTextContent(item?.content);
    total += checked.bytes;
    if (total > MAX_TOTAL_WRITE_BYTES) {
      throw new Error("GitHub write exceeds the total text size bound");
    }
    return { path, content: checked.text };
  });

  const scoped = await writeScoped(env);
  const currentHead = await branchHead(scoped.binding, scoped.token);
  if (currentHead !== expectedHead) {
    return json({
      error: "stale_branch",
      description: "Protected feature branch moved; refresh before writing",
      current_head: currentHead,
    }, 409);
  }

  const baseCommit = await githubJson(
    repoApi(scoped.binding, "/git/commits/" + encodeURIComponent(currentHead)),
    scoped.token,
    "GitHub base commit read",
  );
  const baseTree = requireSha(baseCommit?.tree?.sha, "GitHub base tree");

  const treeEntries = [];
  for (const file of files) {
    const blob = await githubJson(
      repoApi(scoped.binding, "/git/blobs"),
      scoped.token,
      "GitHub blob create",
      {
        method: "POST",
        headers: { "content-type": "application/json; charset=utf-8" },
        body: JSON.stringify({ content: file.content, encoding: "utf-8" }),
      },
    );
    treeEntries.push({
      path: file.path,
      mode: "100644",
      type: "blob",
      sha: requireSha(blob.sha, "GitHub blob"),
    });
  }

  const tree = await githubJson(
    repoApi(scoped.binding, "/git/trees"),
    scoped.token,
    "GitHub tree create",
    {
      method: "POST",
      headers: { "content-type": "application/json; charset=utf-8" },
      body: JSON.stringify({ base_tree: baseTree, tree: treeEntries }),
    },
  );
  const commit = await githubJson(
    repoApi(scoped.binding, "/git/commits"),
    scoped.token,
    "GitHub commit create",
    {
      method: "POST",
      headers: { "content-type": "application/json; charset=utf-8" },
      body: JSON.stringify({
        message,
        tree: requireSha(tree.sha, "GitHub write tree"),
        parents: [currentHead],
      }),
    },
  );
  const commitSha = requireSha(commit.sha, "GitHub write commit");

  await githubJson(
    repoApi(scoped.binding, "/git/refs/heads/" + encodeURIComponent(scoped.binding.branch)),
    scoped.token,
    "GitHub protected feature-branch update",
    {
      method: "PATCH",
      headers: { "content-type": "application/json; charset=utf-8" },
      body: JSON.stringify({ sha: commitSha, force: false }),
    },
  );

  return json({
    committed: true,
    repository: scoped.binding.repository,
    branch: scoped.binding.branch,
    previous_head: currentHead,
    commit_sha: commitSha,
    files: files.map(file => file.path),
  });
}

async function pullRequestEndpoint(request, env) {
  const body = await readJsonBody(request, 32_000);
  await requirePairing(env, body.pairing_secret);
  const title = requirePullTitle(body.title);
  const pullBody = requirePullBody(body.body);

  const scoped = await writeScoped(env);
  await branchHead(scoped.binding, scoped.token);
  await branchHead(scoped.binding, scoped.token, scoped.binding.prBase);

  const query = new URLSearchParams({
    state: "open",
    head: scoped.binding.owner + ":" + scoped.binding.branch,
    base: scoped.binding.prBase,
    per_page: "10",
  });
  const open = await githubJson(
    repoApi(scoped.binding, "/pulls?" + query.toString()),
    scoped.token,
    "GitHub pull request lookup",
  );
  if (!Array.isArray(open)) {
    throw new Error("GitHub pull request lookup returned invalid data");
  }
  if (open.length > 1) {
    throw new Error("Multiple open pull requests exist for the protected feature branch");
  }

  if (open.length === 1) {
    const number = Number(open[0].number);
    if (!Number.isInteger(number) || number < 1) {
      throw new Error("GitHub pull request number is invalid");
    }
    const updated = await githubJson(
      repoApi(scoped.binding, "/pulls/" + number),
      scoped.token,
      "GitHub pull request update",
      {
        method: "PATCH",
        headers: { "content-type": "application/json; charset=utf-8" },
        body: JSON.stringify({
          title,
          body: pullBody,
          maintainer_can_modify: false,
        }),
      },
    );
    return json({
      action: "updated",
      number,
      url: String(updated.html_url || ""),
      draft: Boolean(updated.draft),
      head: scoped.binding.branch,
      base: scoped.binding.prBase,
    });
  }

  const created = await githubJson(
    repoApi(scoped.binding, "/pulls"),
    scoped.token,
    "GitHub draft pull request create",
    {
      method: "POST",
      headers: { "content-type": "application/json; charset=utf-8" },
      body: JSON.stringify({
        title,
        body: pullBody,
        head: scoped.binding.branch,
        base: scoped.binding.prBase,
        draft: true,
        maintainer_can_modify: false,
      }),
    },
  );
  const number = Number(created.number);
  if (!Number.isInteger(number) || number < 1) {
    throw new Error("GitHub pull request number is invalid");
  }
  return json({
    action: "created",
    number,
    url: String(created.html_url || ""),
    draft: true,
    head: scoped.binding.branch,
    base: scoped.binding.prBase,
  });
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (url.pathname === "/health" && request.method === "GET") {
        return json({
          ok: true,
          service: "lyra-github-connector",
          auth: "github-app-installation",
          configured: Boolean(
            String(env.GITHUB_APP_PRIVATE_KEY || "").trim() &&
            String(env.LYRA_PAIRING_SECRET || "").trim() &&
            String(env.GITHUB_APP_ID || "").trim() &&
            String(env.GITHUB_PR_BASE || "").trim()
          ),
          write_model: "broker-gated",
        });
      }
      if (url.pathname === "/github/token") {
        return await tokenEndpoint(request, env);
      }
      if (url.pathname === "/github/write/check") {
        return await writeCheckEndpoint(request, env);
      }
      if (url.pathname === "/github/write/commit") {
        return await commitEndpoint(request, env);
      }
      if (url.pathname === "/github/write/pull-request") {
        return await pullRequestEndpoint(request, env);
      }
      return json({ error: "not_found" }, 404);
    } catch (error) {
      const status = Number(error?.status);
      return json(
        {
          error: String(error?.code || "connector_request_rejected"),
          description: error instanceof Error ? error.message : "Request rejected",
        },
        Number.isInteger(status) && status >= 400 && status <= 599 ? status : 400,
      );
    }
  },
};
