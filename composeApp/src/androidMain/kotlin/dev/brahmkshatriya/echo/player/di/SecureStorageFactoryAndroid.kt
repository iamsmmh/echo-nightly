package dev.brahmkshatriya.echo.player.di

actual fun createSecureStorage(name: String): dev.brahmkshatriya.echo.player.security.SecureStorage =
    dev.brahmkshatriya.echo.player.security.AndroidSecureStorage(dev.brahmkshatriya.echo.player.platform.EchoPlayerAndroid.appContext, name)
