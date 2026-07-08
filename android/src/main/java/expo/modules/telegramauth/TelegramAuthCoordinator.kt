package expo.modules.telegramauth

import android.app.Activity
import android.net.Uri
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import org.telegram.login.TelegramLogin

/**
 * Singleton bridging the [TelegramAuthLifecycleListener] (return-hop intents) with the
 * [ExpoTelegramAuthModule] (pending JS promise + events).
 *
 * All methods are synchronized: entry points span the module queue (login/cancel), the main
 * thread (onNewIntent), and the SDK's Main-dispatcher coroutine callbacks.
 */
internal object TelegramAuthCoordinator {
  const val ON_RETURN_URL_RECEIVED_EVENT = "onReturnUrlReceived"

  const val ERR_CANCELLED = "ERR_CANCELLED"
  const val ERR_DISMISSED = "ERR_DISMISSED"
  const val ERR_NO_AUTH_CODE = "ERR_NO_AUTH_CODE"
  const val ERR_SERVER = "ERR_SERVER"
  const val ERR_REQUEST_FAILED = "ERR_REQUEST_FAILED"
  const val ERR_CONCURRENT = "ERR_CONCURRENT"

  private class PendingLogin(
    val promise: Promise,
    val redirectHost: String?,
    val fallbackScheme: String?,
  ) {
    /** Dedupes double delivery (launch intent + onNewIntent on singleTask relaunch). */
    var handledUrl: String? = null
  }

  private var module: ExpoTelegramAuthModule? = null
  private var pending: PendingLogin? = null

  @Synchronized
  fun attach(instance: ExpoTelegramAuthModule) {
    module = instance
  }

  @Synchronized
  fun detach(instance: ExpoTelegramAuthModule) {
    if (module === instance) module = null
  }

  @Synchronized
  fun login(activity: Activity, options: LoginOptions, promise: Promise) {
    if (pending != null) {
      promise.reject(CodedException(ERR_CONCURRENT, "Another Telegram login is already in progress.", null))
      return
    }

    val redirectUri = runCatching { Uri.parse(options.redirectUri) }.getOrNull()
    pending = PendingLogin(promise, redirectUri?.host, options.fallbackScheme)

    try {
      TelegramLogin.init(options.clientId, options.redirectUri, options.scopes)
      // Fire-and-forget: opens the Telegram app via a tg:// VIEW intent (or the SDK's
      // Custom-Tab web auth if that fails). The result arrives as a return-hop VIEW
      // intent handled by TelegramAuthLifecycleListener → handleReturnUrl below.
      TelegramLogin.startLogin(activity)
    } catch (e: Throwable) {
      finish { it.reject(CodedException(ERR_REQUEST_FAILED, e.message ?: "Failed to start Telegram login", e)) }
    }
  }

  /**
   * Rejects the pending login as user-dismissed. Called from JS when the app returns to the
   * foreground without a return-hop intent (the SDK has no signal for "backed out of Telegram").
   */
  @Synchronized
  fun cancelPending() {
    finish { it.reject(CodedException(ERR_DISMISSED, "Telegram login was dismissed by the user.", null)) }
  }

  /**
   * Routes a return-hop URI into the SDK. Returns `true` only for URIs that belong to the
   * pending login (matching redirect host or fallback scheme); everything else — unrelated
   * deep links, cold-start deliveries with no pending login — is left untouched.
   */
  @Synchronized
  fun handleReturnUrl(uri: Uri): Boolean {
    val current = pending ?: return false

    val matchesHost = uri.scheme == "https" && uri.host != null && uri.host == current.redirectHost
    val matchesScheme = current.fallbackScheme != null && uri.scheme == current.fallbackScheme
    if (!matchesHost && !matchesScheme) return false

    val key = uri.toString()
    if (current.handledUrl == key) return true
    current.handledUrl = key

    // Lets JS cancel its dismissal grace timer before the (slow) token exchange starts.
    module?.emitReturnUrlReceived()

    // Map Telegram's OAuth error params ourselves for stable error codes — the SDK
    // collapses them into a bare message string.
    val error = uri.getQueryParameter("error")
    if (error != null) {
      val description = uri.getQueryParameter("error_description") ?: error
      val code = if (error == "access_denied") ERR_CANCELLED else ERR_REQUEST_FAILED
      finish { it.reject(CodedException(code, description, null)) }
      return true
    }
    if (uri.getQueryParameter("code").isNullOrBlank()) {
      finish { it.reject(CodedException(ERR_NO_AUTH_CODE, "No authorization code in response URI", null)) }
      return true
    }

    try {
      TelegramLogin.handleLoginResponse(
        uri,
        onSuccess = { data ->
          finish { it.resolve(mapOf("idToken" to data.idToken)) }
        },
        onError = { loginError ->
          val message = loginError.message
          val code = if (message.startsWith("HTTP ")) ERR_SERVER else ERR_REQUEST_FAILED
          finish { it.reject(CodedException(code, message, null)) }
        }
      )
    } catch (e: Throwable) {
      // The SDK `requireNotNull`-throws on stale deliveries ("No active login session") —
      // e.g. when the process died while the user was in Telegram. Never let it crash the app.
      finish { it.reject(CodedException(ERR_REQUEST_FAILED, e.message ?: "Failed to handle Telegram login response", e)) }
    }
    return true
  }

  @Synchronized
  private fun finish(complete: (Promise) -> Unit) {
    val current = pending ?: return
    pending = null
    complete(current.promise)
  }
}
