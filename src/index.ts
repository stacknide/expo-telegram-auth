import { watchForDismissal } from './dismissal'
import type {
	TelegramAuthErrorCode,
	TelegramLoginOptions,
	TelegramLoginResult,
} from './ExpoTelegramAuth.types'
import ExpoTelegramAuthModule from './ExpoTelegramAuthModule'

export type {
	TelegramAuthErrorCode,
	TelegramLoginOptions,
	TelegramLoginResult,
} from './ExpoTelegramAuth.types'

/**
 * Whether the native module is available (Android/iOS build with the module linked).
 * `false` on web — keep your web login flow. Note this does NOT validate your
 * BotFather registration or entitlements — only that the native side exists.
 */
export function isNativeLoginSupported(): boolean {
	return ExpoTelegramAuthModule != null
}

/**
 * Whether the Telegram app is installed (resolves a `tg://` VIEW intent on Android,
 * `canOpenURL("tg://resolve")` on iOS). Always `false` on web.
 *
 * Android callers should gate {@link login} on this: without the Telegram app the
 * SDK falls back to a Custom-Tab web login whose return hop reintroduces the
 * browser→app handoff this module exists to avoid. The iOS SDK's built-in
 * `ASWebAuthenticationSession` fallback has no such problem.
 */
export async function isTelegramAppInstalled(): Promise<boolean> {
	if (ExpoTelegramAuthModule == null) return false
	return ExpoTelegramAuthModule.isTelegramAppInstalled()
}

/**
 * Starts the native Telegram login and resolves with the OIDC `idToken` once the
 * user approves inside the Telegram app (app-to-app, no browser). Rejections carry a
 * stable {@linkcode TelegramAuthErrorCode} in `error.code` — treat `ERR_CANCELLED`
 * and `ERR_DISMISSED` as silent no-ops.
 *
 * Config is per-call; there is no init step. Only one login may be in flight at a
 * time (`ERR_CONCURRENT`).
 */
export async function login(options: TelegramLoginOptions): Promise<TelegramLoginResult> {
	if (ExpoTelegramAuthModule == null) {
		throw new TelegramAuthUnsupportedError()
	}
	const loginPromise = ExpoTelegramAuthModule.login({
		clientId: options.clientId,
		fallbackScheme: options.fallbackScheme,
		redirectUri: options.redirectUri,
		scopes: normalizeScopes(options.scopes),
	})
	watchForDismissal(ExpoTelegramAuthModule, loginPromise)
	return loginPromise
}

/**
 * Rejects the in-flight login with `ERR_DISMISSED` and clears native state. Resolves
 * even when there is nothing pending.
 *
 * Two uses: the dismissal watcher calls it when the app returns to the foreground with
 * no return hop, and a caller that hits `ERR_CONCURRENT` can call it to clear state
 * stranded by an earlier attempt before retrying (see the README's `ERR_CONCURRENT` note).
 */
export async function cancelPendingLogin(): Promise<void> {
	if (ExpoTelegramAuthModule == null) return
	return ExpoTelegramAuthModule.cancelPending()
}

/**
 * Claims a login that completed while no JS runtime was listening — resolving the
 * `idToken` the user already approved, or `null` when there is nothing to claim.
 *
 * Android destroys the Activity and React host of a backgrounded app freely, and a native
 * login is *always* backgrounded (it launches Telegram), so the runtime that called
 * {@linkcode login} is frequently gone by the time the user approves. Its promise dies with
 * it. Rather than discard the approval, native stashes the result; call this on mount and
 * finish the flow exactly as if {@linkcode login} had resolved.
 *
 * Delivers at most once, and only within a short window (the `idToken` is short-lived).
 * Resolves `null` on builds that predate the native stash, so it is safe to call
 * unconditionally.
 */
export async function claimStashedLogin(): Promise<TelegramLoginResult | null> {
	// Feature-detected rather than called directly: on a build without the native function,
	// invoking it would reject, and "no stashed login" is the honest answer there.
	if (typeof ExpoTelegramAuthModule?.claimStashedResult !== 'function') return null
	return ExpoTelegramAuthModule.claimStashedResult()
}

/**
 * Reads the stable {@linkcode TelegramAuthErrorCode} off an error thrown by
 * {@linkcode login}, or `null` for unrelated errors.
 */
export function getTelegramAuthErrorCode(error: unknown): TelegramAuthErrorCode | null {
	if (typeof error !== 'object' || error == null) return null
	const code = (error as { code?: unknown }).code
	return typeof code === 'string' && code.startsWith('ERR_')
		? (code as TelegramAuthErrorCode)
		: null
}

class TelegramAuthUnsupportedError extends Error {
	code: TelegramAuthErrorCode = 'ERR_NOT_SUPPORTED'

	constructor() {
		super('expo-telegram-auth is not available on this platform/build.')
		this.name = 'TelegramAuthUnsupportedError'
	}
}

/**
 * The Android SDK force-includes `openid`; the iOS SDK does not. Normalizing here
 * keeps the requested scopes — and therefore the id_token claims — identical on
 * both platforms.
 */
function normalizeScopes(scopes: string[] | undefined): string[] {
	const normalized = scopes ? [...scopes] : []
	if (!normalized.includes('openid')) normalized.unshift('openid')
	return normalized
}
