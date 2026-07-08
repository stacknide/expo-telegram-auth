# Keep the vendored official Telegram Login SDK API surface
# (mirrors the consumer rules shipped with org.telegram:login-sdk).
-keep class org.telegram.login.TelegramLogin { public *; }
-keep class org.telegram.login.LoginData { *; }
-keep class org.telegram.login.LoginError { *; }
