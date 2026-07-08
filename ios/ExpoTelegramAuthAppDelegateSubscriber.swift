import ExpoModulesCore

/**
 Intercepts the Telegram return hop without any app-side wiring:
 - universal link (`https://app{appid}-login.tg.dev/...`) → `continue userActivity`
 - custom-scheme fallback → `open url`

 Subscribers are observe-only in Expo (every subscriber still receives the URL, and so
 does the router) — the coordinator filters strictly by the pending login's redirect
 host/scheme before forwarding to the SDK.
 */
public class ExpoTelegramAuthAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(
    _ app: UIApplication,
    open url: URL,
    options: [UIApplication.OpenURLOptionsKey: Any] = [:]
  ) -> Bool {
    return TelegramAuthCoordinator.shared.handleIfMatches(url)
  }

  public func application(
    _ application: UIApplication,
    continue userActivity: NSUserActivity,
    restorationHandler: @escaping ([any UIUserActivityRestoring]?) -> Void
  ) -> Bool {
    guard userActivity.activityType == NSUserActivityTypeBrowsingWeb,
          let url = userActivity.webpageURL else {
      return false
    }
    return TelegramAuthCoordinator.shared.handleIfMatches(url)
  }
}
