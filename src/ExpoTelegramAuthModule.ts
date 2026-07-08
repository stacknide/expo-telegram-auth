import { type NativeModule, requireOptionalNativeModule } from 'expo'
import type { TelegramLoginResult } from './ExpoTelegramAuth.types'

export type ExpoTelegramAuthEvents = {
	/**
	 * Fired the moment a matching return-hop URL reaches the app, before the token
	 * exchange starts — cancels the dismissal grace timer (the exchange can take
	 * longer than any sensible grace period).
	 */
	onReturnUrlReceived: () => void
}

export declare class ExpoTelegramAuthNativeModule extends NativeModule<ExpoTelegramAuthEvents> {
	isTelegramAppInstalled(): Promise<boolean>
	login(options: {
		clientId: string
		redirectUri: string
		scopes: string[]
		fallbackScheme?: string
	}): Promise<TelegramLoginResult>
	/** Rejects the pending login (ERR_DISMISSED) and clears native state. */
	cancelPending(): Promise<void>
}

/** `null` on web and on native builds that don't include the module. */
export default requireOptionalNativeModule<ExpoTelegramAuthNativeModule>('ExpoTelegramAuth')
