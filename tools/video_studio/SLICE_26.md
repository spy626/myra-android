# LYRA Video Studio — Slice 26: verified camera continuity

This isolated quality slice addresses the visible foundation → structure
camera jump observed in Slice25's 30-second MP4. Unlike length-padding
transitions, the change rerenders real original Blender frames.

## Implementation

- The structure camera begins at the previous foundation shot's exact frame-72
  position, orientation and orthographic scale. It ends at the facade shot's
  frame-1 pose so that BOTH adjacent handoffs are protected.
- Blender's internal keyframes interpolate camera position, look-at target and
  zoom over the original six-second structural growth shot.
- Slice25's five independent 72-frame 3D shots stay in original storyboard
  order, duration, output identity, allowed parameters and strict assembly.
- Saved Blender scene camera audit compares two adjacent cuts for real
  camera location, rotation and orthographic lens.
- A decoded RGB24 pixel audit measures all four cuts. Each consecutive-cut
  mean absolute RGB error must not exceed 12/255. This detects big picture
  jumps, not subjective cinematic quality.
- Fidelity remains stylized Workbench 360x640 12fps, not photorealistic.

## Safety / acceptance

The change is intentionally limited to the structure camera, offline
verification scripts, adversarial tests and isolated Actions CI. It does NOT
modify construction geometry, foundation/facade/lot/reveal renderers, Android,
Cloudflare authorization, dispatch, secrets, production infrastructure or main.

Review the output MP4, continuity_audit.json, ten-frame contact sheet and
structure_scene.blend from the CI artifact. Blender and Android CI should be
checked at the exact final SHA. Physical Android testing and human aesthetic
approval remain pending.
