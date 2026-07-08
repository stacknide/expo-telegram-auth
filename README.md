# expo-telegram-auth

Native **Telegram Login** for Expo apps — wraps Telegram's **official** Login SDKs
([Android](https://github.com/TelegramMessenger/telegram-login-android) ·
[iOS](https://github.com/TelegramMessenger/telegram-login-ios)) so login runs
**app-to-app with the Telegram app: no browser, no Custom-Tab return hop, no
"Open in app?" popup**. Resolves with an OpenID Connect `id_token` (JWT) that you
verify on your server.

- **Android**: the forward hop is a `tg://` intent straight into the Telegram app; the
  return hop is a VIEW intent fired *by* the Telegram app to your verified App Link.
  Chromium's same-app safeguard (the thing that makes browser-based OAuth returns show
  the "Continue to your app?" popup) can never apply — there is no browser in the loop.
- **iOS**: same app-to-app flow via Universal Links; if Telegram isn't installed the
  official SDK falls back to an in-app `ASWebAuthenticationSession` sheet on its own.
- **Web**: not supported (`isNativeLoginSupported()` returns `false`) — keep your
  existing web widget/OIDC flow.

Both official SDKs are **vendored at pinned upstream commits** (see `telegramSdk` in
`package.json` and `scripts/sync-upstream.mjs`) — a zero-credential install: no GitHub
Packages PAT, no extra Maven repos, no SPM wiring.

## Installation

```sh
npx expo install expo-telegram-auth
```

## BotFather setup (required)

Open [@BotFather](https://t.me/botfather) → your bot → **Bot Settings → Login Widget →
Native Login** and register each app identity:

- **Android**: package name + the **SHA-256 signing-cert fingerprint**
  (`./gradlew signingReport`, or read it off an installed build). ⚠️ Register **every**
  cert that will sign the app — local debug keystore, EAS keystore, *and* the Play App
  Signing key — or the App Link verification silently fails for the missing one and the
  return hop opens in the browser instead.
- **iOS**: Bundle ID + your 10-character Apple **Team ID**.

Each registration mints a dedicated **App URL** like `https://app123456-login.tg.dev` —
it is per-registration (NOT derived from your client id). That URL is your redirect URI;
Telegram hosts the matching `assetlinks.json` / AASA for you.

## Configuration

Add the config plugin with your per-identity App URLs:

```ts
// app.config.ts
plugins: [
  [
    'expo-telegram-auth',
    {
      android: { appLinkUrl: 'https://app123456-login.tg.dev/tglogin' },
      ios: { universalLink: 'https://app123456-login.tg.dev' },
      // ios: { fallbackScheme: 'myapp' } — custom-scheme fallback (see caveats)
    },
  ],
]
```

The plugin adds the Android `autoVerify` App Link intent filter, the iOS
`applinks:` Associated Domain, `LSApplicationQueriesSchemes: tg` (required for
detecting the Telegram app), and the optional fallback-scheme URL type. Rebuild
natively after changing it (`npx expo prebuild` / a new dev build).

## Usage

```ts
import * as TelegramAuth from 'expo-telegram-auth'

if (TelegramAuth.isNativeLoginSupported() && (await TelegramAuth.isTelegramAppInstalled())) {
  try {
    const { idToken } = await TelegramAuth.login({
      clientId: '<your BotFather client id>',
      redirectUri: 'https://app123456-login.tg.dev/tglogin', // = the plugin's appLinkUrl/universalLink
      scopes: ['openid', 'profile'],
    })
    // Send idToken to YOUR backend and verify it there (see "Verifying the id_token").
  } catch (error) {
    const code = TelegramAuth.getTelegramAuthErrorCode(error)
    if (code === 'ERR_CANCELLED' || code === 'ERR_DISMISSED') return // user backed out — no-op
    throw error
  }
} else {
  // fall back to your existing browser-based flow
}
```

**Android gating tip**: gate on `isTelegramAppInstalled()`. Without the Telegram app the
Android SDK falls back to a Custom-Tab web login whose return hop reintroduces the exact
browser→app popup this module exists to remove — prefer your own browser flow there.
On iOS the built-in `ASWebAuthenticationSession` fallback is fine to use.

### Error codes

`login()` rejections carry a stable `error.code`
(read it with `getTelegramAuthErrorCode`):

| Code | Meaning |
| --- | --- |
| `ERR_CANCELLED` | User denied in Telegram / cancelled the iOS auth sheet. Silent no-op. |
| `ERR_DISMISSED` | User came back from Telegram without deciding — no return hop arrived. Silent no-op. |
| `ERR_NO_AUTH_CODE` | Return URL carried no authorization code. |
| `ERR_SERVER` | Telegram's token endpoint returned a non-200. |
| `ERR_REQUEST_FAILED` | Network/SDK failure (message has the native detail). |
| `ERR_NOT_CONFIGURED` | iOS SDK not configured (shouldn't happen — config is per-call). |
| `ERR_CONCURRENT` | A login is already in flight. |
| `ERR_NOT_SUPPORTED` | Native module unavailable (web / excluded platform). |

## expo-router apps: rewrite the return URL

The return-hop URL is *also* delivered to React Native's Linking (Expo's lifecycle
listeners/app-delegate subscribers observe rather than consume), so expo-router will try
to route `https://app123456-login.tg.dev/tglogin?...` and land on your not-found screen.
Rewrite it in [`+native-intent.tsx`](https://docs.expo.dev/router/advanced/native-intent/):

```ts
// app/+native-intent.tsx
export function redirectSystemPath({ path }: { path: string; initial: boolean }) {
  try {
    if (new URL(path).host === 'app123456-login.tg.dev') {
      return '/' // or the screen the user started the login from
    }
  } catch {}
  return path
}
```

## Verifying the id_token (server side)

The `idToken` is an RS256 JWT signed by Telegram. Verify it on your backend against
Telegram's JWKS before trusting it:

- JWKS: `https://oauth.telegram.org/.well-known/jwks.json`
- `iss`: `https://oauth.telegram.org` · `aud`: your client id · alg `RS256`

See [Telegram's docs on validating ID tokens](https://core.telegram.org/bots/telegram-login#validating-id-tokens).

> **Open question (roadmap)**: should this package ship a Node server adapter (e.g. an
> `expo-telegram-auth/server` entrypoint with a ready-made `verifyTelegramIdToken()`)?
> Opinions welcome in the issues.

## Behavior details & caveats

- **One login at a time**; config (clientId/redirectUri/scopes) is passed per `login()`
  call — there is no init step.
- **Dismissal**: the app-to-app branch is fire-and-forget; if the user backs out of
  Telegram undecided there is no callback. The module watches for the app returning to
  the foreground without a return URL and rejects with `ERR_DISMISSED` after a short
  grace period.
- **Process death**: if Android kills your app while the user is in Telegram, the PKCE
  verifier dies with it — the (cold-start) return delivery is ignored safely and the
  user simply retries. Guarded: it cannot crash the app.
- **`openid` scope** is always requested (the two SDKs disagree on adding it; the module
  normalizes so id_token claims are identical across platforms).
- **Free Apple developer teams** cannot use Associated Domains — without a paid team the
  iOS app-installed branch cannot return to your app. The `fallbackScheme` only covers
  the SDK's web-session branch (< iOS 17.4). Plan on a paid team for production iOS.
- Android `minSdk 23`, iOS 15+ (the pod targets Expo's floor).

## Vendored upstream SDKs

`android/src/main/java/org/telegram/login/` and `ios/vendor/TelegramLogin.swift` are
byte-for-byte copies of Telegram's official SDK sources at the commits pinned in
`package.json` → `telegramSdk`, each with a provenance header. Never edit them by hand:
bump the pin and run `yarn sync-upstream`. See `THIRD_PARTY_LICENSES.md`.

## License

MIT — see [LICENSE](./LICENSE). Telegram SDK sources © Telegram FZ-LLC (see
`THIRD_PARTY_LICENSES.md`).
