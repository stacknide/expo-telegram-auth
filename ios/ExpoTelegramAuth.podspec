require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  # NOTE: never rename this pod to `TelegramLogin` — the vendored SDK source declares a
  # top-level `enum TelegramLogin`, and a module named after a type it contains breaks
  # Swift name lookup.
  s.name           = 'ExpoTelegramAuth'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = package['homepage']
  s.platforms      = {
    :ios => '16.4'
  }
  s.swift_version  = '5.9'
  s.source         = { git: package['repository'] }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  # Explicit list on purpose: our wrapper files + the vendored official Telegram SDK
  # source (synced by scripts/sync-upstream.mjs). No globs, so nothing unexpected from
  # upstream ever gets compiled in.
  s.source_files = [
    'ExpoTelegramAuthModule.swift',
    'ExpoTelegramAuthAppDelegateSubscriber.swift',
    'TelegramAuthCoordinator.swift',
    'vendor/TelegramLogin.swift'
  ]

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule'
  }
end
