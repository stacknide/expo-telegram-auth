package expo.modules.telegramauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import expo.modules.core.interfaces.ReactActivityLifecycleListener

/**
 * Receives the Telegram return-hop VIEW intents on the main Activity.
 *
 * Note (Expo SDK 57): `ReactActivityDelegateWrapper.onNewIntent` always forwards the intent
 * to the React delegate as well, regardless of our return value — so expo-router also sees
 * the return URL. Consuming apps using expo-router should rewrite it in `+native-intent.tsx`
 * (see the README).
 */
class TelegramAuthLifecycleListener : ReactActivityLifecycleListener {
  override fun onCreate(activity: Activity, savedInstanceState: Bundle?) {
    // Cold-start delivery: with a fresh process there is never a pending login (the SDK's
    // PKCE verifier died with the old process), so the coordinator ignores it safely.
    activity.intent?.data?.let { TelegramAuthCoordinator.handleReturnUrl(it) }
  }

  override fun onNewIntent(intent: Intent): Boolean {
    val uri = intent.data ?: return false
    return TelegramAuthCoordinator.handleReturnUrl(uri)
  }
}
