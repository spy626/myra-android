const textEncoder = new TextEncoder();
const EXPECTED_TOKEN_SECONDS_MAX = 3700;
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
    "user-agent": "LYRA-GitHub-Connector/2",
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
    throw new Error(label + " failed (HTTP " + response.status + ")");
  }
  return data;
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
  const branch = requireEnv(env, "GITHUB_BRANCH");
  if (
    branch.length < 1 ||
    branch.length > 200 ||
    branch.startsWith("/") ||
    branch.endsWith("/") ||
    !/^[A-Za-z0-9._/-]+$/.test(branch) ||
    branch.split("/").some(part => !part || part === "." || part === "..")
  ) {
    throw new Error("GITHUB_BRANCH is invalid");
  }
  const account = requireEnv(env, "GITHUB_ACCOUNT_LOGIN");
  if (!/^[A-Za-z0-9-]{1,100}$/.test(account)) {
    throw new Error("GITHUB_ACCOUNT_LOGIN is invalid");
  }
  return { repository, owner: parts[0], name: parts[1], branch, account };
}

async function issueInstallationGrant(env) {
  const binding = expectedBinding(env);
  const appJwt = await githubAppJwt(env);

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
  const installationLogin = String(installation.account?.login || "").trim();

  const grant = await githubJson(
    "https://api.github.com/app/installations/" + installationId + "/access_tokens",
    appJwt,
    "GitHub installation token",
    {
      method: "POST",
      headers: { "content-type": "application/json; charset=utf-8" },
      body: JSON.stringify({
        repositories: [binding.name],
        permissions: {
          actions: "read",
          contents: "read",
        },
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

  const repo = await githubJson(
    "https://api.github.com/repos/" +
      encodeURIComponent(binding.owner) + "/" + encodeURIComponent(binding.name),
    token,
    "GitHub repository verification",
  );
  if (String(repo.full_name || "").toLowerCase() !== binding.repository.toLowerCase()) {
    throw new Error("GitHub repository identity did not match");
  }

  const branchData = await githubJson(
    "https://api.github.com/repos/" +
      encodeURIComponent(binding.owner) + "/" + encodeURIComponent(binding.name) +
      "/branches/" + encodeURIComponent(binding.branch),
    token,
    "GitHub branch verification",
  );
  if (String(branchData.name || "") !== binding.branch) {
    throw new Error("GitHub branch identity did not match");
  }

  return {
    access_token: token,
    expires_in: expiresIn,
    token_type: "bearer",
    login: installationLogin,
    repository: binding.repository,
    branch: binding.branch,
  };
}

async function tokenEndpoint(request, env) {
  if (request.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405, { allow: "POST" });
  }
  const type = String(request.headers.get("content-type") || "").toLowerCase();
  if (!type.includes("application/json")) {
    return json({ error: "content_type_required" }, 415);
  }
  const length = Number(request.headers.get("content-length") || "0");
  if (Number.isFinite(length) && length > 4096) {
    return json({ error: "request_too_large" }, 413);
  }
  const body = await request.json();
  const pairing = requirePairingSecret(body.pairing_secret);
  if (!await pairingMatches(env, pairing)) {
    return json({
      error: "pairing_rejected",
      description: "LYRA pairing key does not match the Cloudflare secret",
    }, 401);
  }
  return json(await issueInstallationGrant(env));
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
            String(env.GITHUB_APP_ID || "").trim()
          ),
        });
      }
      if (url.pathname === "/github/token") {
        return await tokenEndpoint(request, env);
      }
      return json({ error: "not_found" }, 404);
    } catch (error) {
      return json(
        {
          error: "connector_request_rejected",
          description: error instanceof Error ? error.message : "Request rejected",
        },
        400,
      );
    }
  },
};
