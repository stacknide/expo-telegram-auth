# Changelog

## Unreleased

### 🎉 New features

- Initial release: `login()`, `isTelegramAppInstalled()`, `isNativeLoginSupported()`,
  stable error codes, dismissal detection, and a config plugin (Android App Link intent
  filter; iOS Associated Domains, `LSApplicationQueriesSchemes`, custom-scheme fallback).
  Wraps Telegram's official Login SDKs, vendored at pinned commits
  (Android `org.telegram:login-sdk` 1.0.0 · iOS `telegram-login-ios` 1.0.0).
