import { URL } from 'node:url'
import type { ConfigPlugin } from 'expo/config-plugins'
import type { ExpoTelegramAuthPluginProps } from './types'

/**
 * Declares the Telegram return-hop App Link: an `autoVerify` VIEW intent filter for the
 * BotFather-issued App URL host. Telegram serves the `assetlinks.json` for that host
 * (generated from your Native Login registration), so no further setup is needed.
 *
 * Appended to `config.android.intentFilters` (rendered by prebuild's base mods) instead
 * of raw manifest surgery. MainActivity already has `launchMode="singleTask"` in Expo's
 * template, which is what the Telegram SDK requires for the callback Activity.
 */
export const withTelegramAuthAndroid: ConfigPlugin<ExpoTelegramAuthPluginProps> = (
	config,
	props
) => {
	const appLinkUrl = props?.android?.appLinkUrl
	if (!appLinkUrl) return config

	const url = new URL(appLinkUrl)
	config.android = config.android ?? {}
	const intentFilters = config.android.intentFilters ?? []

	const alreadyDeclared = intentFilters.some((filter) => {
		const dataEntries =
			filter.data == null ? [] : Array.isArray(filter.data) ? filter.data : [filter.data]
		return dataEntries.some((data) => data.host === url.host && data.scheme === 'https')
	})
	if (alreadyDeclared) return config

	config.android.intentFilters = [
		...intentFilters,
		{
			action: 'VIEW',
			autoVerify: true,
			category: ['BROWSABLE', 'DEFAULT'],
			data: [
				{
					host: url.host,
					pathPrefix: url.pathname || '/',
					scheme: 'https',
				},
			],
		},
	]
	return config
}
