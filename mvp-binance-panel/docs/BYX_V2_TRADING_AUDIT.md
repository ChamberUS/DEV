# BYX V2 — Trading audit (Step 7)

Scope: what the Trading Desk really does before the V2 port, which fields are real, and how every V2 state maps onto them. Live trading stays OFF. Nothing here adds execution, an endpoint, a credential or a data source.

## 1. Pipeline (before the port)

```
ResearchService (poller thread, every settings.pollSeconds ≥ 2 s, default 3)
   └─ Platform.runLater → research.snapshot.set(Snapshot)
        └─ AppContext listener → TradingService.update(Snapshot)
             └─ provider = (settings.dataSource == MOCK ? MockTradingProvider : ResearchModeTradingProvider)
                  └─ trading.snapshot.set(new TraderSnapshot())      ← a NEW object every poll
                       └─ PanelApp.render → TradingDeskView.onSnapshot → update(t)
```

* There is **no market feed and no account in this build**. `ResearchModeTradingProvider` (the real provider) always returns `feed = "NOT_CONFIGURED"`, no price, no candles, no asks/bids, no account, no equity/PnL/exposure/drawdown, no rows, `mode = RESEARCH`, `trading = DISABLED`. `TradingProviderTest` pins this.
* `MockTradingProvider` (Settings → data source MOCK) returns fictional BTCUSDT data and flags `source = MOCK`; the shell shows the MOCK DATA badge. Mock is a development aid and is never the real state.
* The only cadence is the research poll (3 s). It republishes an identical snapshot, so every Desk update must be idempotent: unchanged data must touch no node.
* No code anywhere in `src/main` places an order, signs a request, holds an exchange key or calls Binance. `grep` for order/execution/Binance endpoints finds only text (labels, guards, the capture process descriptor).

## 2. Components

| Piece | File | What it does today |
|---|---|---|
| `TradingDeskView` | `ui/trader/TradingDeskView.java` | Legacy Desk: 4-row grid (header 60, chart, blotter 190) + 300 px right column. Rebuilds the order-book `VBox` whenever its key string changes; rebuilds the chart node whenever candles change (`new CandleChart` per update). Uses `panel.css` classes (`card`, `desk-*`, `th-bar-*`, `skeleton`). |
| `TraderPage` | `ui/trader/TraderPage.java` | `PageView` base that feeds the `TraderSnapshot`. |
| `TraderScreens` | `ui/trader/TraderScreens.java` | Secondary screens: Markets, Bot, Strategies, Signals, Portfolio, Positions, Orders, Performance, Activity, Settings. Read-only, legacy styling. |
| `CandleChart` | `ui/trader/CandleChart.java` | `Pane` + `Canvas`; draws grid, 5 price labels and candles from `List<Candle>`. Colours come from four invisible "ink" regions styled by `panel.css` (`chart-*-ink`). No timestamps (a `Candle` is OHLC only). |
| `TTable` | `ui/trader/TTable.java` | `TableView<String[]>` factory + `update` that does a minimal diff (set changed rows, add/remove the tail). Pinned by `IncrementalTableTest`. |
| Dock | `shell/DockModel.java` | Reads the same snapshot (feed, trading, backend); not part of the Desk. |

## 3. Data sources and field availability

| Desk field | Source | Real build today | Update rate |
|---|---|---|---|
| Symbol, market | `TraderSnapshot.symbol/market` ← capture config (`research.capture`) | Real when the capture is configured (e.g. ETHUSDT / USD-M-FUTURES), else null → fallback text | 3 s poll (static) |
| Contract type "PERPETUAL" | Constant in the legacy view | Not in the snapshot. The V2 reference shows PERPETUAL; kept as a label only when the market string says so (USD-M futures); otherwise omitted | static |
| Price | `price` | **N/A** (no feed) | n/a |
| 24h change / high / low / volume | `change24hPct`, `high24h`, `low24h`, `volume24h` | **N/A** | n/a |
| Feed status | `feed` (`NOT_CONFIGURED`, `WAITING`, `CONNECTING`, `STALE`, `OFFLINE`, `ERROR`, `UNAVAILABLE`, `MOCK`, …) | `NOT_CONFIGURED` | 3 s poll |
| Last update time | **does not exist** | Added in this step as an optional `feedUpdatedAt` (null = unknown); no provider sets it yet | — |
| Candles | `candles` (OHLC only) | empty | n/a |
| Order book | `asks`, `bids` (`Level(price,size)`, best first) | empty | n/a |
| Recent trades | **`tradeRows` (shared with the Trades tab)** | empty | n/a |
| Equity, Daily PnL, Exposure, Drawdown | `equity`, `dailyPnl`, `exposure`, `drawdown` | **N/A** (no account) | n/a |
| Positions / Orders | `positions`, `orders` counts + `positionRows`, `orderRows` | 0 / empty. With execution OFF none can exist | n/a |
| Signals | `signalRows` | empty (no approved strategy) | n/a |
| Activity | `activityRows` | empty | n/a |
| Bot | `botState` ("RESEARCH / MONITORING"), `mode`, `trading` ("DISABLED"), `strategy`, `signal` | real: Research / Off / "None approved" / N/A | 3 s poll |
| Backend online | `backendOnline` ← research backend | real | 3 s poll |

Dependencies: nothing in the Desk depends on Binance being reachable. The only backend dependency is the local research backend (capture symbol/market, strategy hypotheses). Everything market-shaped is "no feed" until a real provider exists.

Local-only fields: the tab selection, the timeframe selection (only 1m is real: "The current backend supplies 1m candles").

## 4. Findings that shape the port

1. **Recent trades and the Trades tab read the same `tradeRows`** (columns Time, Symbol, Side, Size, Price, Fee). Those are *account fills*, not a market tape. Preserved (no new data semantics); documented as INTENTIONALLY PRESERVED. With no account and no feed both are empty.
2. **`TraderSnapshot` has no timestamp**, so "Data age", "Last tick" and STALE's LAST UPDATE cannot be computed from real data today. They stay UNAVAILABLE with a reason; a nullable field is added so a future provider (and QA fixtures) can supply it. No clock is invented.
3. **The legacy view rebuilds on data change** (order-book `VBox` cleared and refilled, chart node recreated). The V2 Desk updates fixed row pools and the chart in place.
4. **The legacy order book lists asks best-first** (best ask on top). The V2 reference (and every depth view) puts the best ask next to the mid line; the V2 Desk reverses the asks for display. Data order is untouched.
5. **The legacy blotter has 4 tabs** (Positions, Orders, Trades, Signals) plus a "⋯" menu with Bot Activity and Markets. V2 has 5 tabs; Activity moves in, and the two menu destinations stay reachable through the search palette and the rail (nothing removed).
6. **The Markets/Portfolio screens, Bot, Signals, Orders, Positions, Performance, Activity and Settings (`TraderScreens`) remain legacy-hosted** in this step. The Desk is the Step 7 content. See the status doc for the explicit boundary.
7. `CandleChart` colours need the legacy `chart-*-ink` styles; outside the legacy host they would be transparent. The V2 theme defines the same ink classes.

## 5. Live-trading guards (unchanged, tested)

* `TraderSnapshot.trading` defaults to `"DISABLED"`; the Desk renders it as text (Off) and has no control that can change it.
* Order entry is REFERENCE_ONLY in the handoff: the V2 Desk has no order ticket, no buy/sell button, no leverage control.
* `TradingDeskGuardTest` (new) asserts: the Desk exposes no enabled control other than navigation (timeframe, tabs, the blotter tab bar), no code path writes `trading`, and no class under `panel.ui.trader`/`panel.tradeview` references execution endpoints.
