#!/usr/bin/env node

/**
 * sync-upstream.mjs
 *
 * Re-vendors the OFFICIAL Telegram Login SDK sources this module wraps, pinned
 * to the exact upstream commits recorded in package.json's `telegramSdk` block.
 *
 * Why vendoring (instead of a package registry):
 *   - iOS: Telegram ships the SDK as a Swift Package only. Expo autolinking has
 *     no SPM support, so the (single, MIT-licensed) source file is compiled
 *     directly into this module's pod.
 *   - Android: Telegram publishes the SDK to GitHub Packages Maven, which
 *     requires an authenticated PAT even for public packages. Vendoring the
 *     three Kotlin source files gives every consumer a zero-credential install.
 *
 * The vendored files are byte-for-byte upstream content plus a provenance
 * header. NEVER edit them by hand — bump the pin in package.json and re-run:
 *
 *   yarn sync-upstream        (or: node scripts/sync-upstream.mjs)
 *
 * Files written:
 *   android/src/main/java/org/telegram/login/{TelegramLogin,LoginData,LoginError}.kt
 *   ios/vendor/TelegramLogin.swift
 */

import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const pkg = JSON.parse(await readFile(path.join(packageRoot, 'package.json'), 'utf8'))
const pins = pkg.telegramSdk
if (!pins?.android?.commit || !pins?.ios?.commit) {
	console.error('package.json is missing the `telegramSdk.{android,ios}.commit` pins')
	process.exit(1)
}

const ANDROID_SOURCES = ['TelegramLogin.kt', 'LoginData.kt', 'LoginError.kt']
const ANDROID_UPSTREAM_DIR = 'TelegramLogin/src/main/java/org/telegram/login'
const IOS_UPSTREAM_FILE = 'Sources/TelegramLogin/TelegramLogin.swift'

const rawUrl = (repository, commit, filePath) => {
	const repoPath = new URL(repository).pathname // "/TelegramMessenger/…"
	return `https://raw.githubusercontent.com${repoPath}/${commit}/${filePath}`
}

const provenanceHeader = ({ repository, version, commit, filePath }) =>
	[
		'//',
		'// VENDORED FILE — DO NOT EDIT.',
		`// Source: ${repository}`,
		`// File:   ${filePath}`,
		`// Pin:    v${version} @ ${commit}`,
		'// Synced by scripts/sync-upstream.mjs — bump the pin in package.json and re-run to update.',
		'//',
		'',
		'',
	].join('\n')

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

const fetchUpstreamFile = async (pin, filePath, attempt = 1) => {
	const url = rawUrl(pin.repository, pin.commit, filePath)
	const response = await fetch(url)
	if (response.status === 429 && attempt < 4) {
		// raw.githubusercontent.com rate limit — back off and retry
		await sleep(1500 * attempt)
		return fetchUpstreamFile(pin, filePath, attempt + 1)
	}
	if (!response.ok) throw new Error(`GET ${url} → HTTP ${response.status}`)
	const body = await response.text()
	if (!body.trim()) throw new Error(`GET ${url} → empty body`)
	return body
}

const vendorFile = async (pin, upstreamPath, destination) => {
	const body = await fetchUpstreamFile(pin, upstreamPath)
	await mkdir(path.dirname(destination), { recursive: true })
	await writeFile(
		destination,
		provenanceHeader({ ...pin, filePath: upstreamPath }) + body,
		'utf8'
	)
	console.log(`✔ ${path.relative(packageRoot, destination)}  (${pin.commit.slice(0, 7)})`)
}

// Sequential on purpose — parallel fetches trip raw.githubusercontent.com's rate limit (429).
for (const file of ANDROID_SOURCES) {
	await vendorFile(
		pins.android,
		`${ANDROID_UPSTREAM_DIR}/${file}`,
		path.join(packageRoot, 'android/src/main/java/org/telegram/login', file)
	)
}
await vendorFile(pins.ios, IOS_UPSTREAM_FILE, path.join(packageRoot, 'ios/vendor/TelegramLogin.swift'))

console.log('\nAll upstream sources synced. Review the diff before committing.')
