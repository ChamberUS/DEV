# BYX V2 — Research + Capture audit (Step 8)

Scope: what Research Overview and Capture really read, derive and lock before the V2 port. Nothing here runs a scientific job, opens a gate or starts a capture. Scientific gates stay exactly as the app already enforces them.

Legend: **REAL DATA** read from the backend/files · **REAL STATE** a state the app reports · **DERIVED** computed in the UI from real data · **UNAVAILABLE** no source in this build · **LOCKED** a gate.

## 1. Pipeline (before the port)

```
ResearchService (poller thread, every settings.pollSeconds ≥ 2 s, default 3)
   └─ Platform.runLater → research.snapshot.set(Snapshot)      ← new object every poll
        ├─ PanelApp.render → OverviewView.onSnapshot (admin sessions only for Research views)
        └─ AppContext → TradingService.update (Trading, Step 7)

CaptureMonitorService (own worker, every 5 s, ONLY while CaptureView is visible and admin-authorised)
   └─ CaptureProcessProbe.read()  — read-only: process table + capture dir, no start/stop
        └─ Platform.runLater → CaptureMonitorCard.show(CaptureSnapshot)
CaptureMonitorCard: one 1 s Timeline (presentation only: uptime/elapsed/percent from stored anchors)
```

* Research views are only displayed after the router's gate allows them (admin verification); the UI never grants access. `Snapshot.validationStatus` and `Snapshot.finalHoldout` are `final` constants (`"LOCKED"`, `"SEALED"`); `LockedView` (Validation, Paper/Shadow, Live) has no action.
* The monitor service and the card's timer exist only while Capture is visible (`CaptureView.visibility()`); hidden → `captureMonitor.stop()` + `monitor.stop()`.

## 2. Research Overview (`OverviewView`)

| Element | Source | Class |
|---|---|---|
| Dataset id | `Snapshot.datasetId` (short hash) | REAL DATA |
| Partition badge | `Snapshot.partition` (default `"TRAIN"`) | REAL DATA (a constant default today) |
| Label schema badge | `Snapshot.labelSchema` | REAL DATA |
| Research guard TRAIN / VALIDATION / FINAL_HOLDOUT | **hard-coded literals** "TRAIN OPEN", "VALIDATION LOCKED", "FINAL HOLDOUT SEALED" in `guardStrip()` | LOCKED text. TRAIN OPEN is not derived from anything. V2 derives TRAIN from `partition` and keeps the other two tied to the `final` constants |
| Pipeline stages | `PipelineService.stages()` | DERIVED (see §3) |
| Stage badge `STAGE · X · IDLE/RUNNING` | `PipelineService.currentStage()` + `ResearchService.labelsRunning()` | DERIVED |
| KPI Capture LIVE/state + uptime | `Snapshot.capture.recorder()` / `sessionDuration()` | REAL STATE (null → N/A) |
| KPI Sessions + "All labeled / Labels x / y" | `sessionCount`, `labelDone` | REAL DATA |
| KPI Anchors + rows | `anchorCount`, `checkpointSamples` | REAL DATA |
| KPI Hypotheses `n/m` + spec status | `hypotheses` (READY count), `frozenSpecStatus` | DERIVED + REAL |
| Train dataset rows | `labelSchema`, `horizons.size()`, `featureSchema`, `featureDone` / sessions | REAL DATA / DERIVED |
| Sessions strip | `Snapshot.sessions` (`SessionInfo.overall()`) + `capture.currentSession()` while RUNNING | REAL STATE |
| Next allowed step | `PipelineService.currentStage()`; button only navigates to `hypotheses` | DERIVED; **never an action** |
| Attention | `warnings.size()`, failed jobs from `JobManager` | REAL DATA |
| Environments | literals "Open / Locked / Sealed" | LOCKED text |

Real state in an empty environment (backend offline, no project): dataset id N/A, sessions N/A, recorder N/A, hypotheses N/A, 0 warnings.

### Findings
1. **The guard strip is literal text.** It never reads anything. The V2 version keeps VALIDATION and FINAL_HOLDOUT as constants of the model (`Snapshot.validationStatus/finalHoldout`) and shows TRAIN as open only when the real partition is TRAIN. Nothing grants permission.
2. `PipelineService.stages()` calls `StageState.valueOf(capture.recorder())`, which throws for an unknown recorder string. The V2 mapping is defensive (unknown → UNKNOWN); the service is not edited.
3. The legacy session strip is correct about the active session: it is one segment (CAPTURING), either replacing its entry in `sessions` or appended when the list does not have it, and it is counted once. V2 keeps exactly that rule (and tests it); the "34" of the reference is never hard-coded.
4. `warning` always prints "N WARNING(S)", including "0 WARNINGS"; V2 shows an EMPTY state ("No warnings") when there are none.
5. The pipeline is rebuilt only through `update()` (good), but `setAll` of style classes per stage runs on every snapshot and the "capturing" breathing is toggled per tick via `setBreathing`.

## 3. Scientific pipeline mapping

`PipelineService` stages shown by the overview: Capture, Checkpoint (shown as "Dataset"), Features, Pure Mid Labels (shown as "Labels"), Hypotheses, Validation, Execution, Live (Sessions and Paper/Shadow are filtered out, as today).

| Real `StageState` | V2 state | Visual |
|---|---|---|
| READY | complete | positive bar |
| RUNNING / PARTIAL | current | warning bar (Capture RUNNING: accent bar, breathing in FULL while running) |
| PENDING / MISSING / UNKNOWN | pending | disabled bar |
| LOCKED / BLOCKED | locked | hatched disabled bar, name tertiary |
| FAILED | failed | negative bar |

Validation, Execution (pending), Live are fixed by the service: Validation LOCKED, Live LOCKED. A stage is never "complete" to match the reference. At EXPANDED each stage also shows its real one-line summary (`Dataset: 34 sessions`, `Features: 34 / 34`); at 1440/1600 only the name, as the reference does.

## 4. Capture (`CaptureView` + `CaptureMonitorCard`)

| Element | Source | Class |
|---|---|---|
| State chip | `CaptureSnapshot.state()` ∈ RUNNING, STALE, STOPPED, UNKNOWN | REAL STATE (there is no IDLE/DEGRADED/ERROR/RECOVERING in the monitor; none is invented) |
| Symbol · market | `CaptureSnapshot.symbol/market` | REAL DATA |
| Uptime / campaign elapsed / percent | `processStartedAt`, `campaignStartedAt` + clock; presentation timer | DERIVED (no I/O) |
| Current session | `Snapshot.capture.currentSession()` | REAL DATA |
| Growth: captured data, last event | `capturedBytes`, `lastUpdate` | REAL DATA |
| Growth: events, files | literal "NOT REPORTED" | UNAVAILABLE |
| Storage: disk usage/free | `diskFreeBytes`, `diskTotalBytes` | REAL DATA |
| Retry / recovery, scientific integrity (sequence continuity, gaps, clock drift, schema match) | literal "NOT REPORTED" | UNAVAILABLE |
| Warnings | `CaptureSnapshot.warnings` | REAL DATA |
| Process/storage details | pid, started, checked, path | REAL DATA |
| Timeline | **no history exists** ("Retry / recovery history is not reported by the current monitor") | Session bar = DERIVED from uptime; Rotation/Retry/Failure/Recovery rows = UNAVAILABLE |
| Read-only banner | text | LOCKED (monitoring only) |

### Findings
6. The reference timeline rows say "None recorded", which would assert that there were zero retries. The monitor reports no history, so V2 says **"Not reported"**.
7. The card rebuilds the whole detail grid on every monitor result (`details.getChildren().removeIf` + new cards every 5 s). V2 builds once and updates labels.
8. `updateIndicator()` re-creates the dot each call and toggles the `now` marker's breathing; V2 keeps one dot and one animation per RUNNING state.
9. Admin guard: `CaptureView` requires an admin session; a denied session clears the card and stops the service. Preserved.

## 5. Timers, pollers, background updates

| Thing | Lifetime today | V2 contract |
|---|---|---|
| `ResearchService` poller | app session | unchanged |
| `CaptureMonitorService` (5 s) | visible Capture + admin | unchanged; start on show, stop on hide/dispose |
| `CaptureMonitorCard.timer` (1 s) | `start`/`stop` from CaptureView visibility | one timer while visible, zero when hidden/disposed (regression test) |
| Pipeline capture bar / active session breathing | `setBreathing` / `breathe` per update | one animation per RUNNING state; stopped when state ends |
| `JobsView.tick` (1 s, re-audited) | `onShow` play / `onHide` stop (fixed in Step 2) | unchanged; regression test added (hidden 0, visible 1, dispose 0) |

## 6. Gates (must not move)

* VALIDATION: LOCKED (constant). FINAL_HOLDOUT: SEALED (constant). Neither the overview nor the capture screen has any control that changes them; "Open hypotheses" only navigates, and navigation is decided by `ShellRouter`'s gate.
* No V2 control starts a capture, a job, a dataset/feature/label generation or a hypothesis run. QA uses fixtures and stubs only.
* The UI never writes the research guard or scientific state; fixtures are in-memory records.
