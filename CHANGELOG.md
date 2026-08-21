# Changelog

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
