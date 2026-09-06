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

    /**
     * Dedupes double delivery (launch intent + onNewIntent on singleTask relaunch), and — because
     * Android guarantees `onNewIntent` runs **before** `onResume` — doubles as the deterministic
     * answer to *"did the user come back with a result, or empty-handed?"*. See [onActivityResumed].
     */
    var handledUrl: String? = null

    /**
     * The user actually left this Activity for the login (Telegram, or the SDK's Custom Tab).
     *
     * Required because `onResume` also fires for resumes that are not returns from Telegram — a
     * configuration change being the dangerous one, since it would otherwise read as a dismissal and
     * kill a login the user never left. `onUserLeaveHint` is the exact signal: the framework raises
     * it for a user-initiated departure (including our own `startActivity`) and **not** for a
     * rotation or a system-initiated interruption.
     */
    var leftForAuth = false

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

  /** The user left for Telegram (or the SDK's Custom Tab). Arms [onActivityResumed]. */
  @Synchronized
  fun onUserLeftForAuth() {
    pending?.leftForAuth = true
  }

  /**
   * The Activity is back in the foreground. If the user left for the login and no return hop was
   * routed, they came back without completing it — reject as dismissed.
   *
   * **This is decided, not guessed.** Android guarantees `onNewIntent` is delivered before
   * `onResume` ("An activity will always be paused before receiving a new intent, so you can count
   * on onResume() being called after this method"), so by the time this runs, [PendingLogin.handledUrl]
   * is already set for every login that produced a result. Expo's own wrapper widens the margin
   * further: it forwards `onNewIntent` synchronously and defers `onResume` onto a coroutine.
   *
   * This replaces a 3-second JS grace timer that guessed at the same question from `AppState`, on the
   * far side of the bridge where the ordering guarantee no longer holds. It was wrong for **at least
   * 28% of the logins it rejected** — measured over a week, 122 of 432 affected installations
   * completed the exchange anyway, 64% of them within 30 seconds. This is the same mechanism
   * AppAuth-Android uses (`AuthorizationManagementActivity.onResume` → response URI present?
   * complete : cancel), and like AppAuth it needs no timeout.
   *
   * ⚠️ [PendingLogin.handledUrl] is the discriminator, **not** `pending != null`. The token exchange
   * that follows a return hop is a network call that outlives this callback, so a successful login is
   * still pending here — keying off that would kill every login it was meant to protect.
   */
  @Synchronized
  fun onActivityResumed() {
    val current = pending ?: return
    if (!current.leftForAuth || current.handledUrl != null) return
    finish {
      it.reject(CodedException(ERR_DISMISSED, "Returned to the app without completing the Telegram login.", null))
    }
  }

  /**
   * Rejects the pending login as user-dismissed. Kept for the caller that hits [ERR_CONCURRENT] and
   * needs to clear state stranded by an earlier attempt; dismissal itself is now detected natively by
   * [onActivityResumed] and needs no JS involvement.
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
