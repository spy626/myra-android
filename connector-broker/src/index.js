const textEncoder = new TextEncoder();

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

function redirect(location, headers = {}) {
  return new Response(null, {
    status: 302,
    headers: {
      location,
      "cache-control": "no-store",
      ...headers,
    },
  });
}

function requireEnv(env, name) {
  const value = String(env[name] || "").trim();
  if (!value) throw new Error("Connector broker is not configured: " + name);
  return value;
}

function requireUrlSafe(value, label, min = 20, max = 256) {
  const clean = String(value || "").trim();
  if (
    clean.length < min ||
    clean.length > max ||
    !/^[A-Za-z0-9._~-]+$/.test(clean)
  ) {
    throw new Error(label + " is invalid");
  }
  return clean;
}

function requireCode(value) {
  const clean = String(value || "").trim();
  if (clean.length < 10 || clean.length > 512 || /\s/.test(clean)) {
    throw new Error("OAuth code is invalid");
  }
  return clean;
}

function base64Url(bytes) {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

async function hmac(secret, payload) {
  const key = await crypto.subtle.importKey(
    "raw",
    textEncoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return base64Url(
    new Uint8Array(
      await crypto.subtle.sign("HMAC", key, textEncoder.encode(payload)),
    ),
  );
}

async function signedSession(env, state, challenge) {
  const issuedAt = Math.floor(Date.now() / 1000);
  const payload = [state, challenge, issuedAt].join(".");
  const sig = await hmac(requireEnv(env, "OAUTH_SESSION_SECRET"), payload);
  return base64Url(textEncoder.encode(payload)) + "." + sig;
}

async function verifySession(env, token) {
  const parts = String(token || "").split(".");
  if (parts.length !== 2) throw new Error("OAuth session is missing");
  let payload;
  try {
    const padded = parts[0].replace(/-/g, "+").replace(/_/g, "/")
      + "=".repeat((4 - (parts[0].length % 4 || 4)) % 4);
    const bytes = Uint8Array.from(atob(padded), c => c.charCodeAt(0));
    payload = new TextDecoder().decode(bytes);
  } catch {
    throw new Error("OAuth session is invalid");
  }
  const expected = await hmac(requireEnv(env, "OAUTH_SESSION_SECRET"), payload);
  if (expected !== parts[1]) throw new Error("OAuth session signature is invalid");
  const fields = payload.split(".");
  if (fields.length < 3) throw new Error("OAuth session payload is invalid");
  const issuedAt = Number(fields[fields.length - 1]);
  const challenge = fields[fields.length - 2];
  const state = fields.slice(0, fields.length - 2).join(".");
  requireUrlSafe(state, "OAuth state", 20, 160);
  requireUrlSafe(challenge, "PKCE challenge", 43, 128);
  if (!Number.isFinite(issuedAt) || Math.abs(Date.now() / 1000 - issuedAt) > 15 * 60) {
    throw new Error("OAuth session expired");
  }
  return { state, challenge };
}

function cookieValue(request, name) {
  const header = request.headers.get("cookie") || "";
  for (const raw of header.split(";")) {
    const [key, ...rest] = raw.trim().split("=");
    if (key === name) return rest.join("=");
  }
  return null;
}

function sessionCookie(value, maxAge = 900) {
  return [
    "lyra_gh_session=" + value,
    "Path=/github",
    "HttpOnly",
    "Secure",
    "SameSite=Lax",
    "Max-Age=" + maxAge,
  ].join("; ");
}

function workerOrigin(request) {
  const url = new URL(request.url);
  return url.origin;
}

function callbackUrl(request) {
  return workerOrigin(request) + "/github/callback";
}

async function connect(request, env) {
  const url = new URL(request.url);
  const state = requireUrlSafe(url.searchParams.get("state"), "OAuth state", 20, 160);
  const challenge = requireUrlSafe(
    url.searchParams.get("code_challenge"),
    "PKCE challenge",
    43,
    128,
  );
  const appSlug = requireEnv(env, "GITHUB_APP_SLUG");
  if (!/^[A-Za-z0-9-]{1,100}$/.test(appSlug)) {
    throw new Error("GitHub App slug is invalid");
  }
  const session = await signedSession(env, state, challenge);
  return redirect(
    "https://github.com/apps/" + encodeURIComponent(appSlug) + "/installations/new",
    { "set-cookie": sessionCookie(session) },
  );
}

async function installed(request, env) {
  const session = cookieValue(request, "lyra_gh_session");
  const verified = await verifySession(env, session);
  const clientId = requireEnv(env, "GITHUB_CLIENT_ID");
  const authorize = new URL("https://github.com/login/oauth/authorize");
  authorize.searchParams.set("client_id", clientId);
  authorize.searchParams.set("redirect_uri", callbackUrl(request));
  authorize.searchParams.set("state", verified.state);
  authorize.searchParams.set("code_challenge", verified.challenge);
  authorize.searchParams.set("code_challenge_method", "S256");
  authorize.searchParams.set("prompt", "select_account");
  return redirect(authorize.toString());
}

async function callback(request, env) {
  const url = new URL(request.url);
  const session = cookieValue(request, "lyra_gh_session");
  const verified = await verifySession(env, session);
  const state = requireUrlSafe(url.searchParams.get("state"), "OAuth state", 20, 160);
  if (state !== verified.state) throw new Error("OAuth state mismatch");
  const error = url.searchParams.get("error");
  if (error) {
    const target = new URL("lyra://github/callback");
    target.searchParams.set("error", error);
    target.searchParams.set("state", state);
    return redirect(target.toString(), { "set-cookie": sessionCookie("", 0) });
  }
  const code = requireCode(url.searchParams.get("code"));
  const target = new URL("lyra://github/callback");
  target.searchParams.set("code", code);
  target.searchParams.set("state", state);
  return redirect(target.toString(), { "set-cookie": sessionCookie("", 0) });
}

async function exchange(request, env) {
  if (request.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405, { allow: "POST" });
  }
  const body = await request.json();
  const code = requireCode(body.code);
  requireUrlSafe(body.state, "OAuth state", 20, 160);
  const verifier = requireUrlSafe(body.code_verifier, "PKCE verifier", 43, 128);

  const tokenResponse = await fetch("https://github.com/login/oauth/access_token", {
    method: "POST",
    headers: {
      "accept": "application/json",
      "content-type": "application/json",
      "user-agent": "LYRA-GitHub-Connector/1",
    },
    body: JSON.stringify({
      client_id: requireEnv(env, "GITHUB_CLIENT_ID"),
      client_secret: requireEnv(env, "GITHUB_CLIENT_SECRET"),
      code,
      redirect_uri: callbackUrl(request),
      code_verifier: verifier,
    }),
    redirect: "manual",
  });

  if (tokenResponse.status >= 300 && tokenResponse.status < 400) {
    return json({ error: "github_redirect_refused" }, 502);
  }
  const data = await tokenResponse.json();
  if (!tokenResponse.ok || !data.access_token) {
    return json(
      {
        error: "github_exchange_failed",
        description: String(data.error_description || data.error || "GitHub refused the token exchange"),
      },
      400,
    );
  }

  return json({
    access_token: data.access_token,
    expires_in: data.expires_in || null,
    refresh_token: data.refresh_token || null,
    refresh_token_expires_in: data.refresh_token_expires_in || null,
    token_type: data.token_type || "bearer",
  });
}

async function refresh(request, env) {
  if (request.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405, { allow: "POST" });
  }
  const body = await request.json();
  const refreshToken = String(body.refresh_token || "").trim();
  if (
    refreshToken.length < 20 ||
    refreshToken.length > 512 ||
    /\s/.test(refreshToken)
  ) {
    throw new Error("Refresh token is invalid");
  }

  const tokenResponse = await fetch("https://github.com/login/oauth/access_token", {
    method: "POST",
    headers: {
      "accept": "application/json",
      "content-type": "application/json",
      "user-agent": "LYRA-GitHub-Connector/1",
    },
    body: JSON.stringify({
      client_id: requireEnv(env, "GITHUB_CLIENT_ID"),
      client_secret: requireEnv(env, "GITHUB_CLIENT_SECRET"),
      grant_type: "refresh_token",
      refresh_token: refreshToken,
    }),
    redirect: "manual",
  });
  const data = await tokenResponse.json();
  if (!tokenResponse.ok || !data.access_token) {
    return json(
      {
        error: "github_refresh_failed",
        description: String(data.error_description || data.error || "GitHub refused the refresh"),
      },
      400,
    );
  }
  return json({
    access_token: data.access_token,
    expires_in: data.expires_in || null,
    refresh_token: data.refresh_token || null,
    refresh_token_expires_in: data.refresh_token_expires_in || null,
    token_type: data.token_type || "bearer",
  });
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (url.pathname === "/health") {
        return json({ ok: true, service: "lyra-github-connector" });
      }
      if (url.pathname === "/github/connect" && request.method === "GET") {
        return await connect(request, env);
      }
      if (url.pathname === "/github/installed" && request.method === "GET") {
        return await installed(request, env);
      }
      if (url.pathname === "/github/callback" && request.method === "GET") {
        return await callback(request, env);
      }
      if (url.pathname === "/github/exchange") {
        return await exchange(request, env);
      }
      if (url.pathname === "/github/refresh") {
        return await refresh(request, env);
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
