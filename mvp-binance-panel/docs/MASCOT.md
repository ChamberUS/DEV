# Mascot / motion foundation (V2.1P)

Source master: `assets/mascot/source/bloub-default-cycle.mp4` (local, not versioned; SHA-256 `bfc6e0d050827c543e3a8be6a824e5451607dcc2b461100ad0b2a44bf30de206`, 1024x1024, 30 fps, 1368 frames, 45.6 s, no audio, 13.25 MB).

## Why PNG sprite sheets (not MP4)
The packaged runtime has **no `javafx-media`** (no jar, no native libs). Playing MP4 would add a native media stack and codec risk, and MP4 has no alpha (the white canvas would need a video-in-a-box). So: per-state PNG sprite sheets with real alpha, drawn through `ImageView` viewports. No new dependency, no codec, no `MediaPlayer`.

## Source segmentation (verified frame by frame; `mascot-manifest.json` > `sourceSegments`)
The video is a chain of clips; each clip starts with a morph out of the previous clip's last pose (hard cuts at frames 73, 151, 253, 325, 391, 523). Used: IDLE 0-72 (loop 21-71, 4-frame crossfade, contains a natural blink), THINKING 73-150 (loop 95-140, closes at diff 0.09), PROCESSING = orbit clip 1266-1367 (ball -> triangle with orbits -> ball, 8-frame crossfade), SYNCING = comet 866-940 (loop 876-919, two comet cycles), ATTENTION 253-300 (one-shot, "!"), NOTIFICATION 325-375 (one-shot, blue marker), TRANSITION 1240-1266 (dot grows into the ball, 450 ms). Not used (dedupe or identity mismatch): reading eyes 151-252, second "!" 391-522, APPEAR/morph 523-689, first orbit 690-800, triangle with trail 936-1121.

## Remodel
Background removed by connected white regions from the border; white pockets between orbit rings are background, white regions enclosed by the black body (eyes) are kept; anti-aliased edges use colour-to-alpha against white in a 3 px band (no white halo). Fixed 960 px square crop of the 1024 frame (contains the orbits), per-state optical-centre shift (dark-ink centroid of the poster, not the bbox), area-average downscale in premultiplied space to a 384 px logical canvas (never upscaled in the UI: assets are decoded at the size shown). Sprite sheets are cropped to the union bbox of each state (memory follows content). 5.9 MB total.

## Dark UI
The body is near-black and disappears on bg0..bg3, so `MascotStage` draws a slate disc from tokens (centre `text.secondary` high-contrast #C3CADB, edge `text.secondary` #AAB3C7, ring `surface.line` #2A3144, ~12 % accent halo). The artwork sits at 90 % inside the disc. Dark-disc alternatives were rejected: black body and gray thinking dots vanish.

## API
`MascotState` (closed enum: IDLE, THINKING, PROCESSING, SYNCING, ATTENTION, NOTIFICATION, TRANSITION). `MascotView`: `setState`, `setStaticState`, `play` (one-shot), `transitionTo`, `stop`, `setMotionMode`, `dispose`. The UI never names a file. `MascotAssets` owns manifest + images (lazy, background decode, ref-counted leases). `MascotActivity` (show after 250 ms, THINKING -> PROCESSING after 3 s, result never waits), `MascotUsage` (state -> mascot matrix; empty for errors/security).

## Motion modes
FULL animates (loops through `MotionService.loop`, one-shots through `MotionService.play`). REDUCED and OFF show the poster only (REDUCED with a 60 ms fade; one-shots become a brief poster). The app preference (including the system "reduce motion") wins; only the dev gallery overrides per view.

## Usage matrix
Chain data header (one mascot per screen): NOT_CONFIGURED / LIVE = IDLE poster, CONNECTING = THINKING, SYNCING = SYNCING, OFFLINE / STALE = ATTENTION once then poster, NETWORK_MISMATCH / ERROR = no mascot. Lookups slower than 250 ms: THINKING (PROCESSING after 3 s). Workspace change (Trading / Research / BYX): TRANSITION overlay, ~450 ms, FULL only, never waits, not on small clicks. Research: `MascotUsage.forResearch` (no screen wired yet). Never for password/MFA/elevation/security/secret failures or scientific blockers. Startup/login: nothing (no mascot code in AuthScreens/AppContext; a static poster is allowed later).

## Failure policy
Missing/corrupt asset, broken source, invalid manifest, decoder failure: poster, else empty stage; no exception reaches the FX thread. A generation token discards late results; `dispose` releases every image.

## Tools
`tools/mascot/build_mascot_assets.sh <source.mp4> [outDir]` (JDK 21 + ffmpeg, `nice 10`, 2 threads; deterministic, byte-identical on rebuild) runs `Build.java` and `Verify.java` (resolution, layout, 4096 limit, posters with transparent corners, no opaque white at cell borders, loop closure vs the largest legitimate step, size budget). `Analyze.java`, `Bounds.java`, `Loops.java` are the inspection tools used for the segmentation.

## Dev gallery
`MascotGallery` (route `t-mascot-gallery`, not in the rail): command palette entry only when the service reports a configured chain (LOCAL_QA builds). Every state in FULL / REDUCED / OFF, type, duration, frames, and a 64-192 px size ladder. Lazy: nothing is read before it is opened.

## Performance (dev JVM, real window, no network; nice 10)
baseline poster 3 % of one core, RSS 120 MB; IDLE 8.8 %; THINKING 10.1 %; PROCESSING 128 px 7.9 % / 192 px 7.0 %; SYNCING 8.5 %; TRANSITION x3 10.3 %. Threads constant (13). First frame: THINKING 36 ms, SYNCING 81 ms, IDLE 160 ms, PROCESSING 640-670 ms at 128 px (decode of the 4.6 MB sheet in the background; the poster shows meanwhile). PROCESSING at 192 px raised RSS by ~85 MB (JVM does not return heap); it is the heavy state and is only used for real long tasks.
