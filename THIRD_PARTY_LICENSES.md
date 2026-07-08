# Third-party licenses

This package vendors the source code of Telegram's official Login SDKs, pinned to the
upstream commits recorded in `package.json` → `telegramSdk` and synced by
`scripts/sync-upstream.mjs`. The vendored files are byte-for-byte upstream content plus
a provenance header.

## telegram-login-ios (`ios/vendor/TelegramLogin.swift`)

- Upstream: <https://github.com/TelegramMessenger/telegram-login-ios>
- License: **MIT** — Copyright (c) 2026 Telegram FZ-LLC
- The full upstream MIT license text is available at
  <https://github.com/TelegramMessenger/telegram-login-ios/blob/main/LICENSE>.

## telegram-login-android (`android/src/main/java/org/telegram/login/`)

- Upstream: <https://github.com/TelegramMessenger/telegram-login-android>
- License: the upstream repository does not yet include a license file; the maintainers
  have been asked to add one (they distribute the same code publicly as
  `org.telegram:login-sdk` on GitHub Packages). This section will be updated to cite it
  as soon as it lands. All rights in those files remain with Telegram.
