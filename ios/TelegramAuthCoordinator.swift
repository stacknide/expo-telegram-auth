import ExpoModulesCore

/**
 Singleton bridging the app-delegate subscriber (return-hop URLs) with the module
 (pending JS promise + events).

 Entry points span the module queue (`login`/`cancelPending`), the main thread
 (app-delegate callbacks), and the SDK's MainActor completion — all shared state is
 guarded by a lock, and every vendored-SDK call hops to the MainActor (the SDK API
 is `@MainActor`).

 ## Orphaned logins

 This singleton outlives any one JS runtime, while the promise it holds belongs to exactly one. A
 pending login whose owning module has detached is **orphaned** — its promise can never be settled
 because nothing is listening. Such a login must not block the next attempt, and if it *succeeds* its
 `idToken` is stashed for the next runtime to `claimStashedResult()` rather than discarded.

 This matters far less on iOS than on Android, where the OS destroys the Activity and React host of a
 backgrounded app as a matter of routine and a login is *always* backgrounded (it launches Telegram).
 It is kept symmetric deliberately: the JS layer is shared, so a platform that answered
 `claimStashedResult` differently would be a trap rather than an optimisation.
 */
final class TelegramAuthCoordinator {
  static let shared = TelegramAuthCoordinator()

  static let errCancelled = "ERR_CANCELLED"
  static let errDismissed = "ERR_DISMISSED"
  static let errNoAuthCode = "ERR_NO_AUTH_CODE"
  static let errServer = "ERR_SERVER"
  static let errRequestFailed = "ERR_REQUEST_FAILED"
  static let errNotConfigured = "ERR_NOT_CONFIGURED"
  static let errConcurrent = "ERR_CONCURRENT"

  /**
   How long a stashed result stays claimable. The `idToken` is short-lived and the backend validates
   it, so an ancient stash could only produce a confusing exchange failure on app open.
   */
  private static let maxStashAge: TimeInterval = 10 * 60

  private let lock = NSLock()
  private weak var module: ExpoTelegramAuthModule?
  private var pendingPromise: Promise?
  private var expectedHost: String?
  private var fallbackScheme: String?
  /// The module whose JS runtime owns `pendingPromise`; `nil` once that runtime is gone.
  private weak var pendingOwner: ExpoTelegramAuthModule?
  /**
   Whether an owner was ever recorded for the pending login, so a deallocated `pendingOwner` reads as
   orphaned while "never had one" does not. Treating an owner-less login as orphaned from birth would
   stash a result the caller is still awaiting and hang its promise forever.
   */
  private var hasPendingOwner = false
  private var stashedIdToken: String?
  private var stashedAt: Date?
  /**
   A return-hop URL was routed for the pending login — the deterministic answer to *"did the user come
   back with a result?"*, checked by `applicationDidBecomeActive`.

   Not `pendingPromise != nil`: the token exchange that follows a return hop outlives the callback, so
   a successful login is still pending at that moment.
   */
  private var handledReturnUrl = false
  /**
   The user actually left the app for the login. Required because becoming active also happens for
   resumes that are not returns from Telegram (control centre, a system alert), which would otherwise
   read as a dismissal.
   */
  private var leftForAuth = false

  /// No live JS runtime is waiting on `pendingPromise`; settling it would be a no-op.
  private var isPendingOrphaned: Bool {
    hasPendingOwner && pendingOwner == nil
  }

  func attach(module: ExpoTelegramAuthModule) {
    synced { self.module = module }
  }

  func detach(module: ExpoTelegramAuthModule) {
    synced {
      if self.module === module {
        self.module = nil
      }
      // The promise belongs to the runtime going away with this module. The login itself is kept —
      // its return hop may still arrive, and stashing the result is what rescues it.
      if self.pendingOwner === module {
        self.pendingOwner = nil
      }
    }
  }

  /**
   Hands the caller a login that completed while no JS runtime was listening, clearing it so it is
   delivered exactly once. `nil` when there is nothing to claim or the stash has aged out.
   */
  func claimStashedResult() -> String? {
    synced {
      guard let idToken = stashedIdToken, let at = stashedAt else {
        return nil
      }
      stashedIdToken = nil
      stashedAt = nil
      return Date().timeIntervalSince(at) > Self.maxStashAge ? nil : idToken
    }
  }

  func login(options: LoginOptions, promise: Promise) {
    let redirectHost = URL(string: options.redirectUri)?.host
    let alreadyPending: Bool = synced {
      // An orphaned login is not competing for anything — nobody can receive its result. Evicting it
      // keeps one interrupted attempt from bricking Telegram login for the rest of the process.
      if isPendingOrphaned {
        pendingPromise = nil
        hasPendingOwner = false
      }
      if pendingPromise != nil {
        return true
      }
      pendingPromise = promise
      expectedHost = redirectHost
      fallbackScheme = options.fallbackScheme
      pendingOwner = module
      hasPendingOwner = module != nil
      handledReturnUrl = false
      leftForAuth = false
      return false
    }
    if alreadyPending {
      promise.reject(Self.errConcurrent, "Another Telegram login is already in progress.")
      return
    }

    Task { @MainActor in
      TelegramLogin.configure(
        clientId: options.clientId,
        redirectUri: options.redirectUri,
        scopes: options.scopes,
        fallbackScheme: options.fallbackScheme
      )
      // Telegram installed → app-to-app tg:// hop; the result arrives via the
      // app-delegate subscriber → handleIfMatches → TelegramLogin.handle, which fires
      // this completion. Telegram absent → the SDK's built-in ASWebAuthenticationSession
      // fallback fires the same completion internally.
      TelegramLogin.login { result in
        TelegramAuthCoordinator.shared.finish(result)
      }
    }
  }

  /// The user left the app for Telegram. Arms `applicationDidBecomeActive`.
  func applicationDidEnterBackground() {
    synced { if pendingPromise != nil { leftForAuth = true } }
  }

  /**
   The app is foreground again. If the user left for the login and no return hop was routed, they came
   back without completing it — reject as dismissed.

   **Decided, not guessed.** The return hop reaches `handleIfMatches` through the app-delegate
   subscriber (`open url` / `continue userActivity`), which UIKit delivers *before* the app finishes
   becoming active, so `handledReturnUrl` is already set for any login that produced a result.

   This replaces a 3-second JS grace timer that inferred the same thing from React Native's
   `AppState`, and was wrong for at least 28% of the logins it rejected. The Android side does the
   same thing on `onResume`; the mechanism is AppAuth's.

   Note the iOS ASWebAuthenticationSession fallback never backgrounds the app, so `leftForAuth` stays
   false there and this correctly stands aside — that branch has the SDK's own `.cancelled`.
   */
  func applicationDidBecomeActive() {
    let shouldDismiss: Bool = synced { leftForAuth && !handledReturnUrl && pendingPromise != nil }
    guard shouldDismiss else { return }
    finishPromise { $0.reject(Self.errDismissed, "Returned to the app without completing the Telegram login.") }
  }

  /**
   Rejects the pending login as user-dismissed. Kept for the caller that hits `ERR_CONCURRENT` and
   needs to clear state stranded by an earlier attempt; dismissal itself is now detected natively.
   */
  func cancelPending() {
    finishPromise { $0.reject(Self.errDismissed, "Telegram login was dismissed by the user.") }
  }

  /**
   Routes a return-hop URL into the SDK. Returns `true` only for URLs that belong to the
   pending login (matching redirect host or fallback scheme). Everything else must be left
   untouched — `TelegramLogin.handle(_:)` consumes the pending completion for ANY url, so
   an unrelated deep link forwarded mid-login would kill the login.
   */
  func handleIfMatches(_ url: URL) -> Bool {
    let matched: Bool = synced {
      guard pendingPromise != nil else {
        return false
      }
      let matchesHost = url.scheme == "https" && url.host != nil && url.host == expectedHost
      let matchesScheme = fallbackScheme != nil && url.scheme == fallbackScheme
      return matchesHost || matchesScheme
    }
    guard matched else {
      return false
    }

    synced { handledReturnUrl = true }

    // Stable codes for Telegram's OAuth error params — the SDK ignores `error` and would
    // misreport a user denial as "no authorization code".
    let queryItems = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
    if let error = queryItems?.first(where: { $0.name == "error" })?.value {
      let description = queryItems?.first(where: { $0.name == "error_description" })?.value ?? error
      let code = error == "access_denied" ? Self.errCancelled : Self.errRequestFailed
      finishPromise { $0.reject(code, description) }
      return true
    }

    Task { @MainActor in
      // Resolves through the completion passed to TelegramLogin.login above.
      TelegramLogin.handle(url)
    }
    return true
  }

  func finish(_ result: Result<LoginData, Error>) {
    // Stashes instead of resolving when the login was orphaned mid-hop — the approval the user just
    // gave is the one thing here worth preserving across a runtime teardown.
    if case .success(let data) = result {
      finishPromise(stashing: data.idToken) { $0.resolve(["idToken": data.idToken]) }
      return
    }
    if case .failure(let error) = result {
      finishPromise { $0.reject(Self.errorCode(for: error), error.localizedDescription) }
    }
  }

  private func finishPromise(stashing idToken: String? = nil, _ complete: (Promise) -> Void) {
    let promise: Promise? = synced {
      let current = pendingPromise
      let wasOrphaned = isPendingOrphaned
      pendingPromise = nil
      expectedHost = nil
      fallbackScheme = nil
      pendingOwner = nil
      hasPendingOwner = false
      handledReturnUrl = false
      leftForAuth = false
      guard current != nil, wasOrphaned else {
        return current
      }
      // Orphaned: settling is a no-op. A success is stashed; a failure is dropped, since the user
      // either backed out or Telegram refused and resurfacing that on a later launch is noise.
      if let idToken {
        stashedIdToken = idToken
        stashedAt = Date()
      }
      return nil
    }
    if let promise {
      complete(promise)
    }
  }

  private static func errorCode(for error: Error) -> String {
    guard let telegramError = error as? TelegramLoginError else {
      return errRequestFailed
    }
    switch telegramError {
    case .cancelled:
      return errCancelled
    case .noAuthorizationCode:
      return errNoAuthCode
    case .serverError:
      return errServer
    case .requestFailed:
      return errRequestFailed
    case .notConfigured:
      return errNotConfigured
    }
  }

  private func synced<T>(_ body: () -> T) -> T {
    lock.lock()
    defer {
      lock.unlock()
    }
    return body()
  }
}
