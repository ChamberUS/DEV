# V2.1Q - runtime truth + startup responsiveness

Measured on the development JVM (JDK 21, Retina, same Mac that runs the scientific capture), window focused, 30 s idle on the login
screen, no login performed. "Before" = commit f15d664 plus the opt-in trace only.

## Login idle CPU (BrandPanel)

Root cause, proved by JFR (not assumed): 447 of 454 Java CPU samples were on `QuantumRenderer-0`, all under `NGCanvas.renderContent` ->
Marlin rasterizer: the decorative mesh (60 triangles + 101 lines + 42 nodes + 80 stars + gradients) was repainted full-size 30x/s by an
`AnimationTimer`, and the tagline gradient `Text` was re-laid-out at the same rate. It also never paused when the window lost focus.

| scenario | before | after |
|---|---|---|
| FULL, focused | 36.7-38.7 % | 6.4 % (floor 3.5 %, see below) |
| REDUCED, focused | 20.2-21.2 % | 3.8-3.9 % |
| OFF, focused | 3.7 % | 3.5 % |
| FULL, window not focused | 33.9 % | 0.1 % |
| REDUCED, window not focused | - | 0.1 % |

The ~3.5 % floor is not ours: it is present in OFF (nothing of ours runs) and disappears when the window is not focused - the blinking
caret of the focused field keeps the JavaFX pulse loop alive. Packaged bundle (opened with `open`, real settings, FULL): 6.5 %.

What was done: AnimationTimer replaced by a low-frequency `ScheduledExecutorService` clock; the filled mesh moved to a half-resolution
canvas; glow and the wave highlight are scene nodes (opacity / translate), no canvas work; FULL 2.5 frames/s + mesh redraw every 4th
frame; REDUCED 1 frame/s opacity-only; OFF static. Pauses (keeping phase and time, resumes with no jump) when the window is hidden,
minimized or unfocused. Measured rule: any scene change costs ~0.5 % of a core per Hz almost regardless of area (the cost is presenting
the window), and a canvas+text redraw ~2x that, so the budget is spent on the light band, not on repainting the mesh. Visual trade-off:
the wave highlight is now a soft light band over the mesh instead of per-edge brightening; the mesh drifts in ~1.6 s steps (drift
periods are 18-40 s). Needs a human visual check.

## Startup (AppContext was built on the FX thread)

Timeline before (FX thread unless noted, ms):

| step | before | after |
|---|---|---|
| JVM start -> `PanelApp.start` | ~2000-2500 (JavaFX toolkit; not ours) | same |
| SQLite native + runtime.db open | 88-119 | 52 (native pre-loaded in parallel) |
| `AuthorityClient` (signing-identity detection) | 191-218 | 5 (pre-loaded in parallel) |
| `byxBenefits` (HttpClient/TLS built eagerly) | 203 | 3 (HttpClient now lazy) |
| everything else in AppContext | ~100 | ~100 |
| **AppContext total** | **617-979** | **166-200** |
| fonts (10 TTFs; 5 were loaded twice) | 92-96 | V2 fonts only (5), legacy theme fonts after first paint |
| `ByxTheme.apply` | 26 | 26 |
| `AuthScreens` construct (login UI) | ~240 | ~240 |
| `stage.show()` (native window) | ~500-600 | ~500-600 |
| **longest FX stall (start -> first pulse)** | **1.7-1.9 s (cold: 2.6-3.6 s)** | **1.2-1.3 s** |

Classification: MUST_BE_FX = scene/theme/login UI/stage; BACKGROUND_SAFE = SQLite native load, signing-identity detection (now
`StartupWarmup`, parallel to the toolkit boot, class/library loading only); LAZY = HttpClient of the legacy Cosmos adapters;
POST_LOGIN_ONLY = all views (built in `afterLogin`), research, market, chain, mascot, Chain data, capture status, legacy fonts;
UNNECESSARY_AT_STARTUP = duplicate font registration.

The remaining ~1.2 s is login UI construction + native window creation; going lower needs a UI re-architecture (out of scope).
`runtime.db` unavailable still FAILS CLOSED at startup (policy: no silent fallback) - it does not hang.

## Login stays honest

The service launcher already ran off the FX thread. The login now shows its state: "Starting local service..." (button disabled, fields
editable, no spinner), then "Sign in"; on failure/timeout (25 s) a warning "Local service unavailable" with Retry (Retry restarts the
service, never submits credentials). Presentation only: the same verified channel authenticates. Injected test authorities are not gated.

## Scientific capture indicator

Old source: `python -m adaptive_trader.panel_status`, which only recognizes a collector whose argv contains
`<project>/.venv/bin/adaptive-trader`; the real recorder runs from `~/.mvp-binance-capture/runtime/<name>/bin/adaptive-trader`, so the
dock said STOPPED while the capture was healthy.

New source: `CaptureRuntimeResolver` (registered runtime, supervisor/collector identity, campaign, output dir, and the `.part` file the
verified collector holds open) through `ScientificCaptureResolver` -> `ScientificCaptureService` (daemon thread, every 10 s, 6 s hard
timeout, never on the FX thread, read-only) -> dock "Sci. capture" (tooltip "Scientific capture: ..."). Closed model:

* RUNNING (green) coherent supervisor+collector+campaign and recent write on the collector's own open `.part` (or normal rotation).
* DEGRADED (amber) writer silent > 30 s with the process alive; no writer file beyond the 90 s rotation grace; or the LAST CLOSED
  session was rejected (UNRESOLVED_INCIDENT / STREAM_LIVENESS_INVALID) within the last 2 h. Older rejections are history, not state.
* STOPPED (red) only when the resolver proves no collector and no registered supervisor alive.
* UNKNOWN (grey) anything else: resolver error, ambiguity, timeout, not observed yet. UNKNOWN never becomes STOPPED.

Orphan `.part`: never chosen, because only files opened by the verified collector pid count (test with an old orphan + a new active
one). Rotation (10-25 s without an open file): RUNNING "session rotating" inside the grace, so no RUNNING -> STOPPED -> RUNNING flap.
Cost: a full authoritative read is ~260 ms of CPU; between full reads (every 60 s) a verified fast path re-checks pid + start time +
argv + parent + campaign and reads the cached `.part` mtime in ~3-7 ms (falls back to the full read on any divergence, chunk roll or
ambiguity). ~0.4 % of a core on average. `panel_status` stays for the Backend/Research snapshot; nothing else reads its capture field
for the dock, System Status or diagnostics.

## Not done / limits

* FULL login is 6.4 % total, above the 5 % preference (3.5 % of it is the focused caret). Lowering it further means less animation.
* Post-login view construction (~1.4 s FX) is unchanged.
* `StartupTrace` (env `BYX_STARTUP_TRACE=<file>`) is opt-in timing only (labels + ms); no data.
