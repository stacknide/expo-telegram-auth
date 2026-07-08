package expo.modules.telegramauth

import android.content.Intent
import android.net.Uri
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class LoginOptions : Record {
  @Field
  val clientId: String = ""

  @Field
  val redirectUri: String = ""

  @Field
  val scopes: List<String> = emptyList()

  @Field
  val fallbackScheme: String? = null
}

class ExpoTelegramAuthModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoTelegramAuth")

    Events(TelegramAuthCoordinator.ON_RETURN_URL_RECEIVED_EVENT)

    OnCreate {
      TelegramAuthCoordinator.attach(this@ExpoTelegramAuthModule)
    }

    OnDestroy {
      TelegramAuthCoordinator.detach(this@ExpoTelegramAuthModule)
    }

    AsyncFunction("isTelegramAppInstalled") {
      val packageManager = appContext.reactContext?.packageManager
        ?: return@AsyncFunction false
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve"))
      // Resolvable thanks to the `tg` scheme <queries> entry in this module's manifest.
      intent.resolveActivity(packageManager) != null
    }

    AsyncFunction("login") { options: LoginOptions, promise: Promise ->
      val activity = appContext.currentActivity
      if (activity == null) {
        promise.reject(
          CodedException(
            TelegramAuthCoordinator.ERR_REQUEST_FAILED,
            "No foreground Activity to start the Telegram login from.",
            null
          )
        )
        return@AsyncFunction
      }
      TelegramAuthCoordinator.login(activity, options, promise)
    }

    AsyncFunction("cancelPending") {
      TelegramAuthCoordinator.cancelPending()
    }
  }

  internal fun emitReturnUrlReceived() {
    sendEvent(TelegramAuthCoordinator.ON_RETURN_URL_RECEIVED_EVENT, emptyMap<String, Any>())
  }
}
