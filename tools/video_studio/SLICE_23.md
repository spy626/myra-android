# LYRA Video Studio Slice 23 — Real contiguous 12-second construction sequence

## Why this slice

Slices 21/22 each proved an original 6-second real Blender shot. Slice 23 proves they can become a coherent **two-shot sequence with no repeated video or missing frames**. This is a 12-second original render (two independently rendered 6s clips), NOT a 30- or 60-second finished video.

## Implementation

- Existing strict Android/Python Slice8 sample request and Slice20 deterministic storyboard are unchanged.
- `construction_sequence_contract.py` binds the existing Slice21 foundation and Slice22 structure stages to **adjacent source storyboard frames**: 30-second target frames 73-216, or 60-second target frames 145-288. The first actual assembled frame is 1, last is 144.
- `assemble_construction_sequence.py` independently verifies 72 animated PNGs, editable Blender scenes, strict spec manifests and H264/ffprobe for EACH source. It rejects duplicate sources and bad source ownership, order, project, or scene contract. FFmpeg stream-copy concat joins H264 without fake repetition. It decodes every input and output frame and compares all **144 decoded frame hashes**, plus source motion and duration at exactly 12fps. Only then writes sanitized checksums/size evidence.
- `test_construction_sequence_contract.py` contains 13 security/contract tests (new).
- Dedicated CI renders each source from Blender again, on the same disposable no-credential GitHub runner; uploads real 12s MP4 + four-frame contact sheet and checksum metadata for manual quality inspection. GitHub token is not used for user video submission; repository permission is read-only.
- Standard Android APK CI remains an independent required acceptance check on the exact final SHA. Earlier Slice22 final-SHA jobs must also finish successfully. Do NOT conflate separate runs.

## Not done

This is neither true 30/60-second multi-stage footage nor AI text-to-video, photorealistic construction, voiceover, music, user-facing Android rendering or Cloudflare hosting. A visual jump between separate stages may still need transitions/color consistency tuning and human evaluation. No automatic workflow dispatch, deployments, paid AI/GPU, OAuth activation, secrets provisioning, merge or production changes.

## Security

No changes to the production connector, staging flags, `main`, original 6s demonstration or any previous rendered scene. The only approved operator input used by CI is the existing bounded sample-blue-house JSON. Source directories are CLI-local trusted CI paths, never fields in user JSON.

## Next milestone after exact CI GREEN + owner visual inspection

If both real stages look coherent on the MP4/contact sheet, produce a third independent construction stage (walls/roof) and begin building toward 30-second genuine five-stage rendering. Do not call a simple repeated six-second clip a 30-second output.
