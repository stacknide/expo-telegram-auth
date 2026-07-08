export type ExpoTelegramAuthPluginProps = {
	android?: {
		/**
		 * The BotFather Native Login App URL registered for this Android app identity
		 * (package name + signing-cert SHA-256), e.g. `https://app123456-login.tg.dev/tglogin`.
		 * Adds an `autoVerify` App Link intent filter for it — Telegram hosts the matching
		 * `assetlinks.json`, so verification needs nothing else from you. Pass the same
		 * value to `login()` as `redirectUri`.
		 */
		appLinkUrl: string
	}
	ios?: {
		/**
		 * The BotFather Native Login App URL registered for this iOS app identity
		 * (bundle id + team id), e.g. `https://app123456-login.tg.dev`. Adds the
		 * `applinks:` Associated Domain (requires a paid Apple Developer team). Pass the
		 * same value to `login()` as `redirectUri`.
		 */
		universalLink?: string
		/**
		 * Custom URL scheme fallback registered in BotFather (e.g. `myapp`). Registers a
		 * CFBundleURLTypes entry. Pass the same value to `login()` as `fallbackScheme`.
		 */
		fallbackScheme?: string
	}
}
