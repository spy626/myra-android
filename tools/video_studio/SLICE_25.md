# LYRA Video Studio — Slice 25: original 30-second construction timeline

**Draft/experimental offline prototype.** No AI prompt-to-video service is provided.
This slice adds two independently animated original six-second Blender shots:
- Scene 1, seconds 0–6: empty grassy lot -> soil clearing, four perimeter cuts,
  survey guides and survey stakes. No foundation poured.
- Scene 5, seconds 24–30: completed house -> front trim, steps, walkway and
  finishing details with an independent rightward orbit.

Scenes 2–4 are unchanged, originally rendered Blender foundation, structure,
walls/roof/facade shots from Slices 21–24. Assembly matches decoded H264 pixel
hashes across all 360 source frames, rejecting missing, reordered or copied
footage. A 10-frame contact sheet covers scene starts and boundaries.

The proof is **only for validated 30s, one-floor, no-garden construction
requests** at 360×640, 12fps. Flat/gable roofs and approved paint colors use
existing strict request values. Two floors, 60-second targets, garden requests
and arbitrary text-to-video remain unimplemented and must fail closed.

## Security and acceptance

- Existing stable Blender renderers and Android sources remain untouched.
- Blender and FFmpeg run only on a GitHub Actions isolated runner after
  developer branch push; no user-triggered workflow dispatch from Android.
- No Cloudflare deployment, paid provider, secrets, publishing or production jobs.
- A pass means five real Blender shots and a correctly assembled 30-second
  **stylized** H264 artifact, not cinematic quality approval.
- CI success is **not** physical Android acceptance.

Blender gate: .github/workflows/video-studio-slice-25.yml

Review the 30s MP4, editable lot/reveal .blend files, JSON provenance and
contact sheet from the workflow artifact. Visual camera/geometry discontinuity
at some cuts may remain and must be inspected separately.
