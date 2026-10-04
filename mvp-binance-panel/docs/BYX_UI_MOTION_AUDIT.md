# BYX UI motion audit

Source: `../mvp-binance/desing-reference/BYX-MVP Redesign.html`.
SHA-256: `c794c07504392708c683dc084282a1af2853b152e6cabb2eb68a3c4069c748e8`.
Baseline: `feature/byx-ui-redesign-v1`, `0fc6801`. Audit precedes implementation.

All 14 nested page templates and their style/script/inline attributes were inspected,
including Login, Sign-in states, Trading, Research, Capture, four BYX pages,
Markets/Portfolio, Settings/Profile, Command Palette, Overlays and Design System.
The bundler manifest/template was decoded; the outer iframe loader is packaging,
not application interaction. Every product script is the empty
`class Component extends DCLogic{renderVals(){return{}}}`; no event attributes,
class toggles, navigation controller or modal lifecycle implementation exist.
No backdrop-filter, cubic-bezier declaration, pressed/active animation, financial
update animation or chart motion occurs. Unspecified CSS timing is **ease**
(`.25,.1,.25,1`), not ease-in-out. The live indicator explicitly uses ease-in-out
(`.42,0,.58,1`). Design System prose says hover 120ms; executable CSS says 200ms
(except tooltips 120ms), which takes precedence.

Counting: 12 distinct motion contracts, consolidating repeated CSS across pages
and six login bars into one stagger sequence. Three additional static interaction
contracts and five requested lifecycle contracts without evidence are tracked
separately. The table records final implementation parity; QA is recorded below.

| Component / trigger | Initial → final | Duration / delay / easing | Properties | Reference implementation | Native equivalent / status |
|---|---|---|---|---|---|
| Rail hover / enter, exit | t3/transparent → tx/bg3 | 200ms / 0 / ease | color, background | `.rail i{transition:.2s}` + `:hover` | Native paint interpolation; EXACT |
| Rail selection / selected class | normal → bg3/ws + inset 2px | 200ms / 0 / ease | color, background, inset highlight | `.rail i.on` inherits transition | Paint interpolation; indicator position changes immediately (no sliding reference); NEAR |
| Workspace selection / selected class | t2/bg2 → tx/bg3 + inset bottom | 200ms / 0 / ease | color, background, inset highlight | `.sw b{transition:.2s}` / `.sw b.on` | Native paint interpolation; NEAR |
| Cards / page entry | opacity 0, Y=8 → normal | 500ms / 0 / ease | opacity, translateY | `.p{animation:in .5s both}`; `@keyframes in{from{opacity:0;transform:translateY(8px)}}` | Animate cards on navigation, never on data refresh; NEAR |
| Card sibling stagger / page entry | same entry | 500ms / sibling 2=60,3=120,4=180ms, others=0 / ease | delay | `.p:nth-child(2..4)` | Parent sibling order; NEAR |
| Button hover / enter, exit | brightness 1,Y=0 → 1.1,Y=-1 | 200ms / 0 / ease | filter, transform | `.btn{transition:.2s}` / `.btn:hover` | Native lift + brightness approximation; NEAR |
| Input focus / focus, blur | ln/no ring → ion/3px translucent ring | 200ms / 0 / ease | border, shadow | `.in{transition:.2s}` / `.in:focus` | Native border/ring interpolation; NEAR |
| Rail tooltip / enter, exit | opacity 0,X=-4 → opacity 1,X=0 | 120ms / 0 / ease | opacity, transform | `.rail i::after` / `:hover::after` | Native pointer-transparent overlay; NEAR |
| Skeleton / loading | gradient position 0 → -200% | 2200ms / 0 / ease, infinite | background-position | `.sk`; `@keyframes sh{to{background-position:-200% 0}}` | Native gradient offset loop only visible; NEAR |
| Live indicator / live state | opacity 1 → .45 → 1 | 2600ms / 0 / ease-in-out, infinite | opacity | `.lv`; `@keyframes br{50%{opacity:.45}}` | Native two-half cycle; EXACT |
| Research running pipeline / running state | opacity 1 → .45 → 1 | 2400ms / 0 / ease, infinite | opacity | Research inline `animation:br 2.4s infinite` | Same loop with reference-specific timing; EXACT |
| Login ledger bars / entry | opacity 0,Y=8 → .9/.6/.4/.28/.18/.1,Y=0 | 1000ms / 0,120,240,360,480,600ms / ease | opacity, translateY | Six inline `animation:in 1s … both` | Native sequence preserving base opacity; EXACT |
| Table rows / hover | normal → bg2 | instantaneous | background | `.tb>div:not(.th):hover`, no transition | Existing native static hover; EXACT |
| Tabs/segments / selected | selected underline/background | instantaneous | background, highlight | `.tabs .on`, `.seg b.on`, no transition | Existing native static selected state; EXACT |
| Palette result / selection, disabled | bg3 + inset highlight / t3 | instantaneous | background, color | `.pal.sel`, `.pal.off`, no transition | Existing focus/disabled paint and real gates; EXACT |

## Missing motion evidence

Each is **MOTION REFERENCE MISSING**, not authorization to invent movement:

1. Workspace/page crossfade or slide: independent static iframe pages; no switch handler.
2. Palette open/close/backdrop/panel scale/stagger: `.scr` and `.ovl` are static;
   palette markup does not carry `.p`. Need lifecycle JS/CSS or timed recording.
3. User menu/modal/toast open/close: static Overlays boards, no lifecycle handlers.
4. Auth error shake/success transition/OTP step transition: static Sign-in states.
5. Price/chart/table update flash or financial animation: static values, no data handlers.

For each missing contract, the necessary evidence is its actual event handler,
transition/keyframes and trigger, or a frame-timed recording demonstrating them.
Existing functional gates, native feedback and asynchronous authentication stay intact;
reference-visible invented decorative motion will be removed where practical.

## Scope and verification plan

No layout/token/font/icon redesign, service changes, orders, research jobs, real
TRAIN datasets, VALIDATION/FINAL_HOLDOUT, node startup, SSH, broadcasts or funds.
DEVNET stays DEFERRED. Native FULL/REDUCED/OFF preference is reused; reference
`prefers-reduced-motion` disables animations and transitions, so new parity motion
is immediate in REDUCED/OFF. Deterministic timeline seeking tests exercise middle,
end, reversal and rapid switches; native pulse QA complements static screenshots.
Preserve the existing three static smoke files and rerun all three dimensions.

## Implemented parity and intentional differences

15 found interaction contracts = 12 motion + 3 instantaneous states.
**EXACT: 7; NEAR: 8; NOT FEASIBLE: 0.** Five additional lifecycle requests are
**MOTION REFERENCE MISSING** (20 inventory rows/groups including these absences).
Counts concern distinct contracts, not repeated page instances or each bar/keyframe.

| REFERENCE | JavaFX | PARITY | DIFFERENCE | REASON |
|---|---|---|---|---|
| Rail `.on` animates inset shadow | Background/text/path colors interpolate 200ms; shared indicator relocates immediately | NEAR | Highlight relocation/fade differs | Existing shared native indicator is preserved; HTML provides no sliding indicator |
| `.sw b.on` background/color/inset shadow transition | Native background/text interpolate 200ms | NEAR | Bottom border geometry can change immediately | Native border is not an inset CSS box-shadow; approved geometry retained |
| `.p` entry on document mount | Card/header/metric-strip entry 500ms, Y=8, opacity 0→1 | NEAR | Reused pages enter on activation; start armed after layout pulse | Native cached views and rendering lifecycle differ; avoids losing entry during initial CSS/layout |
| `.p:nth-child(2..4)` 60/120/180ms | Same delays by native sibling position | NEAR | Native wrapper hierarchy differs | Static approved layout is preserved instead of rebuilding the scene graph to match HTML siblings |
| `.btn:hover` brightness(1.1), Y=-1 | ColorAdjust brightness .05 and Y=-1 over 200ms | NEAR | Brightness curve is additive, not exact per-channel multiplication | JavaFX ColorAdjust has different pixel math; no new shader/dependency |
| `.in:focus` crisp 3px translucent box-shadow | Interpolated border and radius-3, spread-1 DropShadow | NEAR | Native ring edge/blur and corner sampling differ | CSS box-shadow has no identical native effect |
| Rail `::after` 120ms fade and X=-4→0 | Pointer-transparent native Popup with same timing/distance | NEAR | Popup/window bounds and edge auto-fix differ | Native tooltip avoids rail/content clipping and keeps layout unchanged |
| `.sk` 200%-width gradient, position 0→-200% over 2200ms | Repeating native LinearGradient offset 0→2 over 2200ms | NEAR | Gradient sampling and background-position representation differ | CSS negative percentage with a double-width image offsets it right; native coordinates reproduce direction/period without a browser |

CSS ease and ease-in-out use exact native SPLINE control points. Live and pipeline
loops use two equal half cycles, opacity .45 at midpoint, returning to 1. Login
uses six independent 1s entries with 120ms stagger and the six original opacities.
Reference rail hover transitions now affect background, text and native icon stroke;
no icon rotation/pop or horizontal rail lift is introduced.

Palette `.ovl`/`.scr` and result selection stay immediate, including close/open
reversal and existing gated callbacks. Palette results do not inherit button hover
motion; its search is not treated as reference `.in`. OTP card mounts use the
shared `.p` entry; error/step/success-specific shake/fade/pause were removed because
there is no evidence for those lifecycles. Authentication is still asynchronous,
and errors, email+SMS verification, trusted-device and AdminSession checks remain.
Unreferenced menu/dialog/toast fades/scales and decorative shell icon playback were
also removed; native state, notification lifetime and real actions remain.

Trading tables retain their incremental update path. CandleChart and gateways are
unchanged. Snapshot rebuilds install hover/focus/loading hooks but never replay page
entry. BYX Network/Wallet/Benefits/Treasury inherit the shared native card and input
contracts without changes to services, real states or TEST/REAL boundaries. Capture
breathing requires its actual RUNNING state. Pipeline breathing requires the actual
RUNNING stage; no job was started to manufacture this state. Offline dock summaries
remain static; real state is never replaced by prototype values.

## Lifecycle, performance and accessibility

One timeline per property/channel; reversal captures the current painted value.
Replacing a timeline stops the previous one. Detachment and rapid navigation settle
properties and remove pending post-layout callbacks. Loop registrations remove
scene listeners on cancellation/detachment. Preference listeners for transient nodes
are weak and loop preference listeners detach with their scene. Loops pause for
hidden pages/minimized windows; inactive windows do not start visible loops. Toggling
FULL/REDUCED repeatedly creates no duplicate loop registration. No polling timer,
data listener, external dependency or permanent motion watcher was added.

FULL uses reference timings. New parity motion is immediate in REDUCED/OFF and
continuous loops stop/reset, matching the CSS reduced-motion rule. Hover/focus/selected
states still render immediately, as they do when CSS transitions are disabled.
Existing settings remain; no new product preference is introduced. Compatibility
MotionService APIs retain their existing reduced-duration behavior for other callers.
No heap/CPU profiler claim is made: lifecycle bounds are verified by deterministic
registration/channel tests and native pulses, not a long-running production benchmark.

## QA results

- `mvn test`: 202 tests, zero failures/errors/skips (191 prior + 11 motion tests).
- `mvn clean package`: same 202 tests, successful package.
- Motion tests seek timelines directly: initial/mid/final frames, CSS curves/durations,
  reversal, real stylesheet hover/selection, focus/blur, rapid workspace A→B→C,
  palette open→close→open, 20 preference reversals, detached entry/pending callback
  cleanup, skeleton/loop pause/detach/restart. No arbitrary sleeps.
- Preserved `RedesignVisualSmoke.java`, `audit_ui_reference.py`,
  `ui_visual_smoke.sh` unchanged. Existing static smoke: 46 PNGs across
  1440×900, 1600×1000, 1920×1080, OTP stages and USER/expired-admin gate checks.
- Added `MotionParitySmoke.java` + `scripts/ui_motion_smoke.sh`: real JavaFX pulses
  in the actual application with isolated temporary home, real development OTP
  authorization, empty REAL-mode backend and execution disabled. Login, Desk,
  hover/exit, Treasury, rapid Trading→Research→BYX navigation, palette churn and
  reduced motion exercised at all three sizes; 45 initial/mid/final PNGs plus CSV with 382 measured frame samples in the final run.
  Hover state is driven through the native Node property (not OS-pointer automation);
  interpolation/rendering/lifecycle are observed live. Pointer hardware ergonomics
  and end-to-end video comparison with the reference were not measured.
- Evidence: `/tmp/byx-motion-static-qa/` and `/tmp/byx-motion-parity-qa/frames.csv`;
  failures during development exposed missing ScrollPane content hooks and initial
  layout consuming entry time. Both were fixed before the successful final runs.
- Engine `ruff check src tests` passed. Scoped `git diff --check` passed; the global
  check reports preexisting whitespace in unrelated iaos-web Layout.jsx/MyStore.jsx.

No actual dataset, VALIDATION, FINAL_HOLDOUT, research job, SSH, DEVNET, broadcast,
fund transfer, trading activation or real secrets operation was executed. Commits
remain local; no push/deployment was performed.

## Files

Created:

- `docs/BYX_UI_MOTION_AUDIT.md`
- `src/main/java/panel/motion/ReferenceMotion.java`
- `src/test/java/panel/ReferenceMotionTest.java`
- `src/test/java/panel/MotionParitySmoke.java`
- `scripts/ui_motion_smoke.sh`

Changed:

- `src/main/java/panel/app/PanelApp.java`
- `src/main/java/panel/motion/MotionService.java`
- `src/main/java/panel/motion/MotionTokens.java`
- `src/main/java/panel/motion/ViewTransitionService.java`
- `src/main/java/panel/ui/Ui.java`
- `src/main/java/panel/ui/Dialogs.java`
- `src/main/java/panel/ui/OverviewView.java`
- `src/main/java/panel/ui/CaptureMonitorCard.java`
- `src/main/java/panel/ui/auth/AuthShell.java`
- `src/main/java/panel/ui/auth/LoginView.java`
- `src/main/java/panel/ui/auth/TwoFactorView.java`
- `src/main/java/panel/ui/motion/BotAvatar.java`
- `src/main/java/panel/ui/motion/SegmentedSwitch.java`
- `src/main/java/panel/ui/motion/Skeleton.java` (description only)
- `src/main/java/panel/ui/motion/UserMenu.java`
- `src/main/java/panel/ui/toast/ToastHost.java`
- `src/main/resources/panel/byx.css`

All changes are local to the existing feature branch. Preexisting `.agents/`,
`skills-lock.json` and unrelated repository work were preserved.
