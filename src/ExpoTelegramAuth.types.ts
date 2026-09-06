export type TelegramLoginOptions = {
	/** Telegram OAuth client id from @BotFather (Bot Settings → Login Widget). */
	clientId: string
	/**
	 * The redirect URI registered for this app's Native Login entry in @BotFather —
	 * the per-registration App URL, e.g. `https://app123456-login.tg.dev/tglogin`.
	 * Must match the config plugin's `appLinkUrl` (Android) / `universalLink` (iOS).
	 */
	redirectUri: string
	/**
	 * OAuth scopes. `openid` is always included (the module adds it if missing, so
	 * both platforms request identical scopes and return identical id_token claims).
	 */
	scopes?: string[]
	/**
	 * Custom URL scheme registered as the fallback redirect in @BotFather
	 * (e.g. `myapp`). Applies to **both platforms**:
	 * - iOS: used by the SDK's ASWebAuthenticationSession fallback on iOS < 17.4.
	 * - Android: when the redirect URI is a custom scheme (not an App Link), the
	 *   coordinator matches the return hop by this scheme instead of the redirect
	 *   host (see `TelegramAuthCoordinator.handleReturnUrl`).
	 */
	fallbackScheme?: string
}

export type TelegramLoginResult = {
	/** The OpenID Connect id_token (JWT, RS256-signed by oauth.telegram.org). Verify it on your server. */
	idToken: string
}

export type TelegramAuthErrorCode =
	/** The user denied the request (or cancelled the iOS web-auth sheet). Treat as a silent no-op. */
	| 'ERR_CANCELLED'
	/**
	 * The user returned to the app without completing the login — no return hop ever arrived.
	 *
	 * Decided by the platform lifecycle (Android `onResume` after `onNewIntent`, iOS
	 * `applicationDidBecomeActive`), so it is a fact rather than a guess: there is no grace period and
	 * no race with the JS bridge. Treat as a silent no-op.
	 */
	| 'ERR_DISMISSED'
	/** The return URL carried no authorization code. */
	| 'ERR_NO_AUTH_CODE'
	/** Telegram's token endpoint answered with a non-200 status. */
	| 'ERR_SERVER'
	/** A network/SDK failure (message carries the native detail). */
	| 'ERR_REQUEST_FAILED'
	/** iOS SDK reported it was not configured (should not happen — config is per-call). */
	| 'ERR_NOT_CONFIGURED'
	/** A login is already in progress. */
	| 'ERR_CONCURRENT'
	/** Native module unavailable (web, or the platform build does not include it). */
	| 'ERR_NOT_SUPPORTED'
