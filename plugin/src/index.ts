import { type ConfigPlugin, createRunOncePlugin } from 'expo/config-plugins'
import type { ExpoTelegramAuthPluginProps } from './types'
import { withTelegramAuthAndroid } from './withTelegramAuthAndroid'
import { withTelegramAuthIos } from './withTelegramAuthIos'

// eslint-disable-next-line @typescript-eslint/no-var-requires
const pkg: { name: string; version: string } = require('../../package.json')

export type { ExpoTelegramAuthPluginProps } from './types'

const withTelegramAuth: ConfigPlugin<ExpoTelegramAuthPluginProps> = (config, props) => {
	config = withTelegramAuthAndroid(config, props)
	config = withTelegramAuthIos(config, props)
	return config
}

export default createRunOncePlugin(withTelegramAuth, pkg.name, pkg.version)
