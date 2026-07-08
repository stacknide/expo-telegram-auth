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
	 * (e.g. `myapp`). iOS only: used by the SDK's ASWebAuthenticationSession
	 * fallback on iOS < 17.4, and accepted by the return-hop filter.
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
	/** The user left for Telegram but came back without deciding — no return hop ever arrived. Treat as a silent no-op. */
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
