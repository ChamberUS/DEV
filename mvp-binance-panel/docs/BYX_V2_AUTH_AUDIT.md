# BYX V2 — Auth audit (Step 6)

Scope: what authentication really exists in the JavaFX app before the V2 port, and how every V2 auth state maps onto it. No backend is invented; anything without a real capability stays NOT AVAILABLE and never reports success.

## 1. What exists today

| Piece | Where | What it really does |
|---|---|---|
| Accounts | `SqliteUserRepository` (`~/.mvp-binance-panel/panel.db`) | Local users with role ADMIN or USER and status ACTIVE or DISABLED. There is no remote auth server: the "backend" for sign-in is this local store. |
| Password check | `AuthService.login` + `PasswordHasher` | Constant-time check against a dummy hash for unknown accounts (no account enumeration by timing). Failures: `INVALID_CREDENTIALS`, `ACCOUNT_DISABLED`, `RATE_LIMITED` (with `retryAfter`). Every attempt is audited. |
| Rate limiting | `InMemoryRateLimiter(5, 60 s)` | Five failures per identifier, then a lockout with a real remaining duration. |
| Password policy | `PasswordPolicy` | 10+ characters, not equal to the username. (The handoff's 4-rule panel is `DEMO_POLICY`; the real policy wins.) |
| First run | `InitialAdminSetupView` → `UserService.createInitialAdmin` | Only while no user exists: creates the first ADMIN. Not in the handoff, real, kept. |
| Forced password change | `ChangePasswordView` → `UserService.changeOwnPassword` | After an administrator sets a temporary password (`mustChangePassword`). |
| Admin-set password | `UsersView` → `UserService.resetPassword` | An administrator sets a temporary password; the user must change it at next sign-in. This is the real recovery path. |
| User session | `SessionManager` / `UserSession` | Created at sign-in, ended at logout. **No expiry in the backend.** Logout listeners stop capture monitoring and pause the BYX node. |
| Admin authorization | `AdminAccessService` / `AdminSession` | Separate step-up for Research: email OTP then SMS OTP (`TwoFactorFlow`), or a Keychain-backed trusted device. Expires after `security.admin.sessionTimeoutMinutes` (default 30) of inactivity. |
| OTP | `OtpService` (email, 6 digits, local HMAC, cooldown 30 s, 10 min flow) · `TwilioVerifySmsProvider` (SMS, code length set by the Twilio Verify service) · `ResendEmailOtpProvider` | Real when the providers are configured (`setup-local-2fa.sh`, Keychain). `DevOtpProvider` only with `security.dev.mode=true`, labelled DEVELOPMENT AUTH PROVIDER. |
| Trusted device | `TrustedDeviceService` | "Trust this Mac for 30 days" after a full 2FA; revocable in Profile. Checked asynchronously on a worker thread before the 2FA screen. |
| Logout | `AuthService.logout` | Audited; ends both sessions. |
| Session expiry handling | `PanelApp.watchAdminSession` (every 1 s and 10 s) | Admin session expiry: toast and back to Trading. `AccessDecision.SESSION_EXPIRED` (no user session): back to Login with a notice. |
| Screens | `AuthShell`, `LoginView`, `TwoFactorView`, `InitialAdminSetupView`, `ChangePasswordView` | Legacy styling, hosted in the entry `LegacyHost` since step 5. The admin 2FA screen sits in the shell content area. |

The ghost navigation of the original app came from the trusted-device check finishing late. Since step 5 that callback completes through `ShellRouter.complete(ticket, …)` and does nothing if the ticket is no longer current.

## 2. V2 state → real capability

| V2 screen / state | Real capability | Decision |
|---|---|---|
| Login · default, focus, loading | `AuthService.login` on a worker thread | **REAL** |
| Login · invalid credentials | `INVALID_CREDENTIALS` | **REAL**, neutral copy (never says which part is wrong) |
| Login · account disabled | `ACCOUNT_DISABLED` | **REAL** (extra state, not in the handoff) |
| Login · rate limited (countdown) | `RATE_LIMITED.retryAfter` | **REAL**; the countdown comes from the service, then the form re-enables |
| Login · server unavailable | Any unexpected failure reading the local account store | **REAL**, copy adapted: there is no network server, so it says the account store could not be read |
| Login · 2FA required | Not at sign-in: 2FA is a Research step-up | **NOT A SIGN-IN STATE**. The V2 2FA visuals are used for the real admin verification |
| Login · success | Session created → router opens the workspace | **REAL**; the router navigates, no animation decides |
| Create account / Register | No registration endpoint; accounts are created by an administrator | **BACKEND_REQUIRED · not reachable** (handoff QA: without an endpoint the screen is unreachable). Login says accounts are created by an administrator |
| Verify email | No registration, no email verification API | **BACKEND_REQUIRED · not reachable** |
| Forgot password | No self-service reset; an administrator can set a temporary password | **BACKEND_REQUIRED · NOT AVAILABLE screen**: explains the administrator path; never says an email was sent |
| Reset password (token) | No token flow | **BACKEND_REQUIRED · not reachable**. The real forced change (`ChangePasswordView`) is ported to V2 instead |
| 2FA sign-in step (email → SMS) | `TwoFactorFlow` | **REAL** (AVAILABLE_IF_EXISTING_API) |
| 2FA · trust this device | `TwoFactorFlow.finish(true)` | **REAL**, off unless chosen |
| 2FA · providers not configured | `TwoFactorNotConfiguredException` + `ProviderStatus` | **REAL** state (NOT CONFIGURED, with provider status) |
| 2FA setup, QR, recovery codes | None | **BACKEND_REQUIRED · not reachable**; no secrets in the client |
| Session expired dialog → sign in → return | `SESSION_EXPIRED` | **REAL path** (user session gone while the app is open); return target from the route state at that moment |
| Admin authorization expired | `expireIfNeeded` | **REAL**, unchanged behaviour (toast, back to Trading) through the router |
| First run (initial admin) | `createInitialAdmin` | **REAL**, kept, restyled V2 |
| Public footer (About, FAQ, Terms, Privacy) | About → Credits dialog exists; FAQ/Terms/Privacy are step 11 | About **REAL**; the others **COMING SOON** (disabled, reason shown) |

## 3. Port plan
- One route authority: auth panes are routes of the same `ShellRouter` (`auth:login`, `auth:forgot`, `auth:setup`, `auth:change-password`); the gate allows them only without an app session (change-password only for a session that must change it).
- V2 auth layout: brand region + pane (520/560/640, padding 64/64/96, form 392/432/448), brand field per P3.20 (FULL animated at ≤ 30 fps, REDUCED glow breath only, OFF one static frame), footer links.
- Login states rendered from the real `AuthService` outcome; loading disables every control so no stale result can land on another pane.
- Admin 2FA in V2 visuals inside the shell: email step with 6 OTP boxes (local 6-digit code), SMS step with a single digits field (length owned by Twilio Verify), cooldown from the flow, trust device opt-in, NOT CONFIGURED state with provider status.
- Session expired: persistent alert dialog (layer 70, one action) → Login → return to the route captured at expiry if it still exists and is not gated, otherwise Trading.
