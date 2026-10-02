# Third-party assets and credits

Acquired 2026-10-02. Nothing is downloaded at runtime; every asset below is bundled locally.

| Internal name | File | Origin | Author / platform | Source page | License | Attribution | Used in |
|---|---|---|---|---|---|---|---|
| Inter Regular/Medium/SemiBold/Bold | `fonts/Inter-*.ttf`, `fonts/Inter-OFL.txt` | GitHub release v4.0 (`extras/ttf`) | Rasmus Andersson / The Inter Project Authors | https://github.com/rsms/inter | SIL OFL 1.1 | License file shipped; credit in About / Credits | Whole UI |
| JetBrains Mono Regular/Medium/Bold | `fonts/JetBrainsMono-*.ttf`, `fonts/JetBrainsMono-OFL.txt` | GitHub release v2.304 | JetBrains | https://github.com/JetBrains/JetBrainsMono | SIL OFL 1.1 | License file shipped; credit in About / Credits | Prices, numbers, logs |
| Lottie4J `fxplayer` 1.2.6 (library, Maven Central) | n/a | Maven Central | Lottie4J contributors | https://github.com/lottie4j/lottie4j | Apache-2.0 | Credit in About / Credits | Native Lottie renderer (no WebView) |
| pulse-ring | `animations/original/pulse-ring.json` | Written for this project | This project | n/a | Original work | None | "bot" icon (when Lottie is enabled) |
| check-pop | `animations/original/check-pop.json` | Written for this project | This project | n/a | Original work | None | "check" icon (success) |
| Native icon set | `panel/motion/icon/IconPaths.java` | Drawn for this project (SVG paths) | This project | n/a | Original work | None | Sidebar, empty states, toasts, menus |

## Evaluated and NOT used

* **Lordicon** (https://lordicon.com). Free tier = CC BY-ND 4.0 with modifications (author attribution required, downloads tied to an account, icons must not be modified). Downloading requires an account, which could not be created on the project's behalf, and recoloring would violate ND. No Lordicon asset is bundled. If one is added later, add the credit "Animated icons by Lordicon.com" to About / Credits (desktop software: About page) and record the file here.
* **Lottieflow** (https://lottieflow.com). Requires sign-up and no license/terms page could be found for the files (the site targets Webflow). Because the license is not sufficiently clear, no Lottieflow asset is bundled.
* **libraries.dev skill** (`.agents/skills/libraries-dev`). React effect libraries; used only as a design reference (timing, placement rules). No code or package from it is included.
