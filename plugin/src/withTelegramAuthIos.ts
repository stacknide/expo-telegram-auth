import { URL } from 'node:url'
import { type ConfigPlugin, withEntitlementsPlist, withInfoPlist } from 'expo/config-plugins'
import type { ExpoTelegramAuthPluginProps } from './types'

const ASSOCIATED_DOMAINS_KEY = 'com.apple.developer.associated-domains'

/**
 * iOS build-time wiring:
 * - `LSApplicationQueriesSchemes: tg` — always added; without it `canOpenURL("tg://…")`
 *   silently answers false and both the SDK's app detection and
 *   `isTelegramAppInstalled()` misreport "not installed".
 * - Associated Domain `applinks:<host>` for the BotFather App URL (paid Apple team only).
 * - A CFBundleURLTypes entry for the custom-scheme fallback, if configured.
 */
export const withTelegramAuthIos: ConfigPlugin<ExpoTelegramAuthPluginProps> = (config, props) => {
	const { universalLink, fallbackScheme } = props?.ios ?? {}

	config = withInfoPlist(config, (cfg) => {
		const querySchemes = new Set(
			(cfg.modResults.LSApplicationQueriesSchemes as string[] | undefined) ?? []
		)
		querySchemes.add('tg')
		cfg.modResults.LSApplicationQueriesSchemes = [...querySchemes]

		if (fallbackScheme) {
			const urlTypes = cfg.modResults.CFBundleURLTypes ?? []
			const alreadyRegistered = urlTypes.some((urlType) =>
				urlType.CFBundleURLSchemes.includes(fallbackScheme)
			)
			if (!alreadyRegistered) {
				cfg.modResults.CFBundleURLTypes = [...urlTypes, { CFBundleURLSchemes: [fallbackScheme] }]
			}
		}
		return cfg
	})

	if (universalLink) {
		config = withEntitlementsPlist(config, (cfg) => {
			const domains = new Set(
				(cfg.modResults[ASSOCIATED_DOMAINS_KEY] as string[] | undefined) ?? []
			)
			domains.add(`applinks:${new URL(universalLink).host}`)
			cfg.modResults[ASSOCIATED_DOMAINS_KEY] = [...domains]
			return cfg
		})
	}

	return config
}
