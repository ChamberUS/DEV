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
separately. Status below initially PENDING; final parity/QA is recorded after implementation.

| Component / trigger | Initial → final | Duration / delay / easing | Properties | Reference implementation | Native equivalent / status |
|---|---|---|---|---|---|
| Rail hover / enter, exit | t3/transparent → tx/bg3 | 200ms / 0 / ease | color, background | `.rail i{transition:.2s}` + `:hover` | Native paint interpolation; PENDING |
| Rail selection / selected class | normal → bg3/ws + inset 2px | 200ms / 0 / ease | color, background, inset highlight | `.rail i.on` inherits transition | Paint interpolation; indicator position changes immediately (no sliding reference); PENDING |
| Workspace selection / selected class | t2/bg2 → tx/bg3 + inset bottom | 200ms / 0 / ease | color, background, inset highlight | `.sw b{transition:.2s}` / `.sw b.on` | Native paint interpolation; PENDING |
| Cards / page entry | opacity 0, Y=8 → normal | 500ms / 0 / ease | opacity, translateY | `.p{animation:in .5s both}`; `@keyframes in{from{opacity:0;transform:translateY(8px)}}` | Animate cards on navigation, never on data refresh; PENDING |
| Card sibling stagger / page entry | same entry | 500ms / sibling 2=60,3=120,4=180ms, others=0 / ease | delay | `.p:nth-child(2..4)` | Parent sibling order; PENDING |
| Button hover / enter, exit | brightness 1,Y=0 → 1.1,Y=-1 | 200ms / 0 / ease | filter, transform | `.btn{transition:.2s}` / `.btn:hover` | Native lift + brightness approximation; PENDING |
| Input focus / focus, blur | ln/no ring → ion/3px translucent ring | 200ms / 0 / ease | border, shadow | `.in{transition:.2s}` / `.in:focus` | Native border/ring interpolation; PENDING |
| Rail tooltip / enter, exit | opacity 0,X=-4 → opacity 1,X=0 | 120ms / 0 / ease | opacity, transform | `.rail i::after` / `:hover::after` | Native pointer-transparent overlay; PENDING |
| Skeleton / loading | gradient position 0 → -200% | 2200ms / 0 / ease, infinite | background-position | `.sk`; `@keyframes sh{to{background-position:-200% 0}}` | Native gradient offset loop only visible; PENDING |
| Live indicator / live state | opacity 1 → .45 → 1 | 2600ms / 0 / ease-in-out, infinite | opacity | `.lv`; `@keyframes br{50%{opacity:.45}}` | Native two-half cycle; PENDING |
| Research running pipeline / running state | opacity 1 → .45 → 1 | 2400ms / 0 / ease, infinite | opacity | Research inline `animation:br 2.4s infinite` | Same loop with reference-specific timing; PENDING |
| Login ledger bars / entry | opacity 0,Y=8 → .9/.6/.4/.28/.18/.1,Y=0 | 1000ms / 0,120,240,360,480,600ms / ease | opacity, translateY | Six inline `animation:in 1s … both` | Native sequence preserving base opacity; PENDING |
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
