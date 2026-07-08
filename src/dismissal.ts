import { AppState, type AppStateStatus } from 'react-native'
import type { ExpoTelegramAuthNativeModule } from './ExpoTelegramAuthModule'

/**
 * How long after returning to the foreground we wait for the return-hop URL before
 * treating the login as dismissed. The return intent can lag the AppState transition
 * by a moment; the `onReturnUrlReceived` native event disarms the timer as soon as a
 * matching URL arrives (the token exchange itself may far outlast this grace period).
 */
const DISMISSAL_GRACE_PERIOD_MS = 3000

/**
 * The app-to-app login branch is fire-and-forget: if the user backs out of Telegram
 * undecided, no return hop ever arrives and the native promise would hang forever.
 *
 * Watcher semantics:
 * - arms a grace timer only after a real background→active transition (launching
 *   Telegram backgrounds the app; the iOS ASWebAuthenticationSession fallback does NOT
 *   background the app — there the SDK's own `.cancelled` covers dismissal, and this
 *   watcher stays inert),
 * - disarms on the `onReturnUrlReceived` event,
 * - on expiry asks native to reject the pending login with `ERR_DISMISSED`.
 */
export function watchForDismissal(
	nativeModule: ExpoTelegramAuthNativeModule,
	loginPromise: Promise<unknown>
): void {
	let graceTimer: ReturnType<typeof setTimeout> | undefined
	let returnUrlReceived = false
	let wasBackgrounded = false
	let settled = false

	const disarm = () => {
		if (graceTimer !== undefined) {
			clearTimeout(graceTimer)
			graceTimer = undefined
		}
	}

	const returnUrlSubscription = nativeModule.addListener('onReturnUrlReceived', () => {
		returnUrlReceived = true
		disarm()
	})

	const appStateSubscription = AppState.addEventListener('change', (state: AppStateStatus) => {
		if (state === 'background') {
			wasBackgrounded = true
			return
		}
		if (state !== 'active' || !wasBackgrounded || settled || returnUrlReceived) return
		disarm()
		graceTimer = setTimeout(() => {
			if (settled || returnUrlReceived) return
			// Rejects the still-pending native login promise with ERR_DISMISSED.
			nativeModule.cancelPending()
		}, DISMISSAL_GRACE_PERIOD_MS)
	})

	const cleanup = () => {
		settled = true
		disarm()
		returnUrlSubscription.remove()
		appStateSubscription.remove()
	}
	loginPromise.then(cleanup, cleanup)
}
