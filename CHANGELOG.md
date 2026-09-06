# Changelog

## 0.3.0

### 🐛 Bug fixes

- **`ERR_DISMISSED` is no longer a guess, and no longer wrong.** Dismissal was inferred in JS by
  arming a 3-second timer on React Native's `AppState` and rejecting if no return hop landed. That
  ran on the far side of the bridge, where Android's `onNewIntent`-before-`onResume` guarantee no
  longer holds, so the only remedy for a slow hop was a bigger constant. In production it rejected
  logins that were about to succeed: over one week, **122 of 432 affected installations (28%)
  completed the exchange anyway, 64% of them within 30 seconds** — a floor, since only some successes
  are observable. Detection now happens natively on `onResume` / `applicationDidBecomeActive`, keyed
  on whether a return URL was routed, which the lifecycle guarantees is already known by then. This is
  the mechanism AppAuth-Android uses.

### 💥 Breaking changes

- Removed the `onReturnUrlReceived` event. It existed solely to disarm the JS grace timer and has no
  other consumer.

### 🧹 Removed

- `src/dismissal.ts`, `DISMISSAL_GRACE_PERIOD_MS`, and the `AppState` listener. **There is no longer a
  tunable timing constant anywhere in this module.**

### 📚 Documentation

- `ERR_CANCELLED` is documented as **unreachable on the Android app-to-app path**. Declining inside
  the Telegram app closes the sheet and sends no redirect at all — it does not even return you to the
  calling app — so a decline surfaces as `ERR_DISMISSED`. Verified on device. It remains reachable on
  the Custom-Tab fallback, which does perform a standard OAuth `error=access_denied` redirect.

### ✅ Verified on device

Xiaomi/MIUI, Android 14, Telegram installed. Dismissal now lands **110 ms** after the Telegram
activity finishes (was 3,000 ms): approve ✅ · back out ✅ · rotate mid-login ✅ (no false dismissal) ·
Home mid-login ✅ · immediate reconnect ✅ · **"Don't keep activities" approve ✅** · back out under DKA
then retry ✅ · force-stop mid-login ✅ · double-tap ✅.

Two notes from that session, neither a defect in this module:

- Declining inside Telegram surfaces as `ERR_DISMISSED`, not `ERR_CANCELLED` — see Documentation above.
- The SDK's own Custom-Tab fallback was **not** exercised, and is unreachable from a caller that gates
  `login()` on `isTelegramAppInstalled()` as this README recommends. Such a caller supplies its own web
  flow instead.

## 0.2.0

### 🎉 New features

- `claimStashedLogin()` — claims a login the user approved while no JS runtime was
  listening. Android destroys the Activity and React host of a backgrounded app freely,
  and a native login *always* backgrounds the app (it launches Telegram), so the runtime
  that called `login()` is frequently gone by the time approval comes back. Native now
  stashes that result instead of discarding it. Delivers at most once, within a 10-minute
  window (the `idToken` is short-lived).
- `cancelPendingLogin()` — rejects the in-flight login with `ERR_DISMISSED` and clears
  native state. Resolves even when nothing is pending.

### 🐛 Bug fixes

- A login whose owning runtime was torn down mid-flow no longer stays pending forever.
  Previously `detach` cleared only the module reference, so every later `login()` rejected
  with `ERR_CONCURRENT` for the life of the process, and an approval the user actually gave
  resolved a dead promise and was silently discarded. An orphaned pending is now evicted by
  the next `login()` rather than blocking it.

### 📚 Documentation

- `claimStashedLogin()` is documented as a **mandatory** call — see the upgrade note below.
- Corrected the `ERR_CONCURRENT` / `ERR_DISMISSED` guidance: `ERR_DISMISSED` is inferred
  from a grace timer, so it also fires on real approvals whose return hop was slow. Callers
  must not treat it as a certain cancel.
- `fallbackScheme` applies to the Android scheme-redirect return hop too.

### ⬆️ Upgrading from 0.1.0

Call `claimStashedLogin()` on mount and finish the flow exactly as if `login()` had
resolved. Without it, logins approved after an Android teardown are lost. It is safe to
call unconditionally — it feature-detects the native function and resolves `null` on
builds predating the stash.

```ts
useEffect(function claimOrphanedTelegramLogin() {
  claimStashedLogin().then((result) => {
    if (result) onLoginSuccess(result)
  })
}, [])
```

## 0.1.0 — 2026-07-10

### 🎉 New features

- Initial release: `login()`, `isTelegramAppInstalled()`, `isNativeLoginSupported()`,
  stable error codes, dismissal detection, and a config plugin (Android App Link intent
  filter; iOS Associated Domains, `LSApplicationQueriesSchemes`, custom-scheme fallback).
  Wraps Telegram's official Login SDKs, vendored at pinned commits
  (Android `org.telegram:login-sdk` 1.0.0 · iOS `telegram-login-ios` 1.0.0).
