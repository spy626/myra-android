#!/usr/bin/env node
/**
 * Slice32 optional authenticated READ-ONLY API preflight.
 * The API key must come from a server/GitHub Actions secret, never from
 * argv, Android APK, checked-in config, stdin, or the console.
 * This script invokes ONLY GET /api/v1/key and GET /api/v1/videos/models.
 */
import {readonlyWanVideoPreflight} from "../src/video-openrouter-preflight.mjs";
const report=await readonlyWanVideoPreflight({
  apiKey:process.env.LYRA_OPENROUTER_VIDEO_PREFLIGHT_KEY,
});
console.log(JSON.stringify(report,null,2));
