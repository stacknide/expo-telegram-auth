import ExpoModulesCore

struct LoginOptions: Record {
  @Field
  var clientId: String = ""

  @Field
  var redirectUri: String = ""

  @Field
  var scopes: [String] = []

  @Field
  var fallbackScheme: String?
}

public class ExpoTelegramAuthModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoTelegramAuth")

    Events(TelegramAuthCoordinator.onReturnUrlReceivedEvent)

    OnCreate {
      TelegramAuthCoordinator.shared.attach(module: self)
    }

    OnDestroy {
      TelegramAuthCoordinator.shared.detach(module: self)
    }

    AsyncFunction("isTelegramAppInstalled") { () -> Bool in
      guard let url = URL(string: "tg://resolve") else {
        return false
      }
      // Requires LSApplicationQueriesSchemes to contain "tg" (added by the config plugin);
      // without it iOS answers false instead of erroring.
      return MainActor.assumeIsolated {
        UIApplication.shared.canOpenURL(url)
      }
    }.runOnQueue(.main)

    AsyncFunction("login") { (options: LoginOptions, promise: Promise) in
      TelegramAuthCoordinator.shared.login(options: options, promise: promise)
    }

    AsyncFunction("cancelPending") {
      TelegramAuthCoordinator.shared.cancelPending()
    }
  }
}
