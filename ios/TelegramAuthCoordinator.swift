import ExpoModulesCore

/**
 Singleton bridging the app-delegate subscriber (return-hop URLs) with the module
 (pending JS promise + events).

 Entry points span the module queue (`login`/`cancelPending`), the main thread
 (app-delegate callbacks), and the SDK's MainActor completion — all shared state is
 guarded by a lock, and every vendored-SDK call hops to the MainActor (the SDK API
 is `@MainActor`).
 */
final class TelegramAuthCoordinator {
  static let shared = TelegramAuthCoordinator()

  static let onReturnUrlReceivedEvent = "onReturnUrlReceived"

  static let errCancelled = "ERR_CANCELLED"
  static let errDismissed = "ERR_DISMISSED"
  static let errNoAuthCode = "ERR_NO_AUTH_CODE"
  static let errServer = "ERR_SERVER"
  static let errRequestFailed = "ERR_REQUEST_FAILED"
  static let errNotConfigured = "ERR_NOT_CONFIGURED"
  static let errConcurrent = "ERR_CONCURRENT"

  private let lock = NSLock()
  private weak var module: ExpoTelegramAuthModule?
  private var pendingPromise: Promise?
  private var expectedHost: String?
  private var fallbackScheme: String?

  func attach(module: ExpoTelegramAuthModule) {
    synced { self.module = module }
  }

  func detach(module: ExpoTelegramAuthModule) {
    synced {
      if self.module === module {
        self.module = nil
      }
    }
  }

  func login(options: LoginOptions, promise: Promise) {
    let redirectHost = URL(string: options.redirectUri)?.host
    let alreadyPending: Bool = synced {
      if pendingPromise != nil {
        return true
      }
      pendingPromise = promise
      expectedHost = redirectHost
      fallbackScheme = options.fallbackScheme
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

  /**
   Rejects the pending login as user-dismissed. Called from JS when the app returns to
   the foreground without a return-hop URL (the app-to-app branch has no signal for
   "backed out of Telegram undecided").
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

    // Lets JS cancel its dismissal grace timer before the (slow) token exchange starts.
    synced { module }?.sendEvent(Self.onReturnUrlReceivedEvent, [:])

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
    finishPromise { promise in
      switch result {
      case .success(let data):
        promise.resolve(["idToken": data.idToken])
      case .failure(let error):
        promise.reject(Self.errorCode(for: error), error.localizedDescription)
      }
    }
  }

  private func finishPromise(_ complete: (Promise) -> Void) {
    let promise: Promise? = synced {
      let current = pendingPromise
      pendingPromise = nil
      expectedHost = nil
      fallbackScheme = nil
      return current
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
