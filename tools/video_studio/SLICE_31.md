# LYRA Video Studio Slice 31 — OpenRouter Wan video API, free-only adapter

## Decision
Preserve all completed Slice30 authentic Blender video rendering and Android builds
while beginning the *new* general text-to-video direction requested by the owner.

OpenRouter's public Wan 2.7 and 2.6 free model pages list:
- alibaba/wan-2.7:free
- alibaba/wan-2.6:free

The same catalogue also lists **paid** Wan 2.7 / Wan 2.6 IDs.
A free model webpage alone is not proof of account-level API access. Never
route to the paid identities. Actual user-account video generation requires
a server-held API key and has **not** been performed in this slice.

Official API docs: https://openrouter.ai/docs/api/api-reference/video-generation/create-videos
Model metadata: GET https://openrouter.ai/api/v1/videos/models
Submit jobs: POST https://openrouter.ai/api/v1/videos (async, returns 202)
Check jobs: GET https://openrouter.ai/api/v1/videos/{jobId}
Retrieve content: GET https://openrouter.ai/api/v1/videos/{jobId}/content

## Implemented and independently tested
An isolated Node.js ES module connector-broker/src/video-openrouter-free.mjs
implements the live read-only catalogue check, strict allowlist, runtime
free price checking, 5-second 9:16 short-video validation, safe 480p/720p
parameter choice, server-approved request assembly, asynchronous job
submission, and normalized authenticated polling. It never follows
provider-supplied polling URLs, never accepts paid model IDs or arbitrary
hostnames, and never inserts a provider-specific override or paid fallback.

Before any possible POST, **all** must hold:
- An authorized future trusted server explicitly approves a request.
- That server has independently checked a zero-spend account/key policy.
- A real server-held API key exists; it is not in APK or repository.
- The live authenticated video-model catalogue includes the selected exact
  free model, with all published SKU prices exactly zero, no duplicate IDs,
  and explicit support for the requested video parameters.

The current deployed Cloudflare/GitHub broker and Android UI are **not
connected** to this staging module. The operator flag in the module is only
an additional *kill switch*, never authorization by itself. Future activation
must require the existing trusted owner session, per-owner atomic reservations,
CSRF protection for browser routes, limited job concurrency, a server-side
spend cap, and secure MP4 retrieval/storage. No secret is provisioned.

## Negative acceptance tests
New Node tests enforce no paid WAN slug or :free router substitutions;
no absent/unknown/mixed-price model SKUs; no invalid aspect/resolution/
duration; no extra user fields (including callback URLs, arbitrary provider
options or image refs); no API request without both server flags; always GET
before POST; strict fixed OpenRouter HTTPS base; validated job IDs and
polling paths; safe zero/positive/unknown cost statuses; and no redirects.
All inherited video auth, Node, offline Python, original five-shot Blender
30s H264/360 source frames, camera/geometry continuity, original Cycles and
32-sample FFmpeg NLMeans proof continue running in Slice31 CI. Android
push/PR runs must also pass at the final SHA.

## Realistic result and caveats
This is a **tested API integration prototype, not a deployed video service**.
No authenticated OpenRouter request was made, no credits spent, and no
user-specific free video API availability or generated MP4 proven. The
"zero" model metadata check minimizes accidental charges, but a catalogue
to-submission price race is still possible; only a provider-enforced
zero-spend API key/account policy makes a real paid spend impossible.
Model pricing and availability may change. If the free video model is not
in the authenticated catalogue or cannot make the requested 5-second
9:16 clip, submission fails closed and returns no fake MP4. Phone test
and production deployment remain pending. No edits to main.

## Next strictly scoped live test
Only after explicitly authorizing the future trusted broker and securely
provisioning a zero-spend OpenRouter key, run GET /videos/models, verify
actual zero pricing and supported settings, submit one approved user prompt,
poll with bounded retries, and validate real H264 MP4 bytes/duration before
showing the video. Never enable automatic paid fallback.
