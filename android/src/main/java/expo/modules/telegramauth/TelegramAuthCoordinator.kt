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
 *
 * ## Why this outliving the JS runtime is the hard part
 *
 * This is an `object` — a process-scoped singleton — while the JS promise it holds belongs to one
 * React runtime. Android routinely destroys the Activity and React host of a backgrounded app, and
 * launching Telegram backgrounds us by design, so the runtime that started a login is frequently
 * gone by the time the user comes back. The process, however, usually survives, and so does this.
 *
 * A pending login whose owning module has detached is therefore **orphaned**: its promise can never
 * be settled, because nothing is listening. Two things follow, and both were live bugs:
 *
 * - Orphaned state must not block a new login. It used to, forever — every later attempt got
 *   `ERR_CONCURRENT` for the life of the process, and the app told the user to "try again".
 * - An orphaned login that *succeeds* must not be resolved into the void. The user approved in
 *   Telegram; throwing that away silently is the worst outcome available. It is stashed instead
 *   ([claimStashedResult]) so the next runtime can finish the job.
 */
internal object TelegramAuthCoordinator {
  const val ON_RETURN_URL_RECEIVED_EVENT = "onReturnUrlReceived"

  const val ERR_CANCELLED = "ERR_CANCELLED"
  const val ERR_DISMISSED = "ERR_DISMISSED"
  const val ERR_NO_AUTH_CODE = "ERR_NO_AUTH_CODE"
  const val ERR_SERVER = "ERR_SERVER"
  const val ERR_REQUEST_FAILED = "ERR_REQUEST_FAILED"
  const val ERR_CONCURRENT = "ERR_CONCURRENT"

  /**
   * How long a stashed result stays claimable. The `idToken` is short-lived and the backend
   * validates it, so an ancient stash could only produce a confusing exchange failure on app open.
   * Generous enough to cover a cold start on a slow device plus the user navigating back to the
   * Telegram screen; short enough that a forgotten login does not resurface as an error later.
   */
  private const val MAX_STASH_AGE_MS = 10 * 60 * 1000L

  private class PendingLogin(
    val promise: Promise,
    val redirectHost: String?,
    val fallbackScheme: String?,
    /**
     * The module whose JS runtime owns [promise]. Cleared by [detach] when that runtime goes away,
     * which is what makes this login *orphaned* — see the class docs.
     */
    var owner: ExpoTelegramAuthModule?,
  ) {
    /** Whether an owner was ever recorded, so "never had one" cannot read as "lost it". */
    private val hadOwner = owner != null

    /** Dedupes double delivery (launch intent + onNewIntent on singleTask relaunch). */
    var handledUrl: String? = null

    /**
     * No live JS runtime is waiting on [promise]; settling it would be a no-op.
     *
     * Requires [hadOwner]: treating an owner-less login as orphaned from birth would make
     * [finishOrStash] stash a result the caller is still awaiting, hanging its promise forever.
     * Resolving into a possibly-dead promise is the safe direction of that guess.
     */
    val isOrphaned: Boolean
      get() = hadOwner && owner == null
  }

  private class StashedResult(val idToken: String, val stashedAtMs: Long)

  private var module: ExpoTelegramAuthModule? = null
  private var pending: PendingLogin? = null
  private var stashed: StashedResult? = null

  @Synchronized
  fun attach(instance: ExpoTelegramAuthModule) {
    module = instance
  }

  @Synchronized
  fun detach(instance: ExpoTelegramAuthModule) {
    if (module === instance) module = null
    // The promise belongs to the runtime going away with this module. Keep the login itself: the
    // return hop may still be on its way, and stashing its result is what rescues it.
    if (pending?.owner === instance) pending?.owner = null
  }

  /**
   * Hands the caller a login that completed while no JS runtime was listening, clearing it so it is
   * delivered exactly once. `null` when there is nothing to claim or the stash has aged out.
   */
  @Synchronized
  fun claimStashedResult(): String? {
    val current = stashed ?: return null
    stashed = null
    if (System.currentTimeMillis() - current.stashedAtMs > MAX_STASH_AGE_MS) return null
    return current.idToken
  }

  @Synchronized
  fun login(activity: Activity, options: LoginOptions, promise: Promise) {
    // An orphaned login is not competing for anything — nobody can receive its result. Evicting it
    // is what keeps a single interrupted attempt from bricking Telegram login for the whole process.
    if (pending?.isOrphaned == true) pending = null

    if (pending != null) {
      promise.reject(CodedException(ERR_CONCURRENT, "Another Telegram login is already in progress.", null))
      return
    }

    val redirectUri = runCatching { Uri.parse(options.redirectUri) }.getOrNull()
    pending = PendingLogin(promise, redirectUri?.host, options.fallbackScheme, module)

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
          // Stashes instead of resolving when the login was orphaned mid-hop — the approval the
          // user just gave is the one thing here worth preserving across a runtime teardown.
          finishOrStash(data.idToken) { it.resolve(mapOf("idToken" to data.idToken)) }
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
    // Settling an orphaned promise is a no-op, so skip it. A *failed* orphaned login is dropped
    // outright rather than stashed: the user either backed out or Telegram refused, and resurfacing
    // that on a later launch would be noise. Clearing `pending` is the part that matters.
    if (current.isOrphaned) return
    complete(current.promise)
  }

  /**
   * [finish], except an orphaned login stashes [idToken] for the next runtime to
   * [claimStashedResult] rather than discarding it.
   */
  @Synchronized
  private fun finishOrStash(idToken: String, complete: (Promise) -> Unit) {
    val current = pending ?: return
    pending = null
    if (current.isOrphaned) {
      stashed = StashedResult(idToken, System.currentTimeMillis())
      return
    }
    complete(current.promise)
  }
}
