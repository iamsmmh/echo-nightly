package dev.brahmkshatriya.echo.player.di

import dev.brahmkshatriya.echo.player.audio.PlaybackController
import dev.brahmkshatriya.echo.player.audio.PlayerEngine
import dev.brahmkshatriya.echo.player.audio.QueueManager
import dev.brahmkshatriya.echo.player.audio.TimeBasedQueueIdGenerator
import dev.brahmkshatriya.echo.player.download.DownloadRepository
import dev.brahmkshatriya.echo.player.extensions.BuiltinExtensionFactory
import dev.brahmkshatriya.echo.player.extensions.ExtensionRuntime
import dev.brahmkshatriya.echo.player.extensions.local.LocalExtensionClient
import dev.brahmkshatriya.echo.player.extensions.local.createLocalExtension
import dev.brahmkshatriya.echo.player.extensions.subsonic.SubsonicApi
import dev.brahmkshatriya.echo.player.extensions.subsonic.createSubsonicExtension
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.library.DefaultStreamResolver
import dev.brahmkshatriya.echo.player.library.FavoritesRepository
import dev.brahmkshatriya.echo.player.library.HistoryRepository
import dev.brahmkshatriya.echo.player.library.LocalLibraryRepository
import dev.brahmkshatriya.echo.player.library.PlaylistRepository
import dev.brahmkshatriya.echo.player.library.SearchRepository
import dev.brahmkshatriya.echo.player.library.SettingsRepository
import dev.brahmkshatriya.echo.player.library.TrackRef
import dev.brahmkshatriya.echo.player.platform.HttpClient
import dev.brahmkshatriya.echo.player.platform.HttpRequest
import dev.brahmkshatriya.echo.player.platform.KeyValueStore
import dev.brahmkshatriya.echo.player.platform.MetadataReader
import dev.brahmkshatriya.echo.player.platform.MusicStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

/**
 * The composition root of the shared player: builds every repository and the
 * [PlaybackController] exactly once, identically on Android and iOS.
 */
class AppGraph(
    val scope: CoroutineScope,
    val logger: EchoLogger,
    engine: PlayerEngine,
    storeName: String = "echo_player"
) {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val store: KeyValueStore by lazy { dev.brahmkshatriya.echo.player.platform.createKeyValueStore(storeName) }
    val settings: SettingsRepository by lazy { SettingsRepository(store) }
    val http: HttpClient by lazy { dev.brahmkshatriya.echo.player.platform.createHttpClient(logger) }
    val storage: MusicStorage by lazy { dev.brahmkshatriya.echo.player.platform.createMusicStorage() }
    val metadataReader: MetadataReader by lazy { dev.brahmkshatriya.echo.player.platform.createMetadataReader() }

    val library: LocalLibraryRepository by lazy {
        LocalLibraryRepository(storage, metadataReader, logger, store)
    }

    val playlists: PlaylistRepository by lazy { PlaylistRepository(store) }
    val favorites: FavoritesRepository by lazy { FavoritesRepository(store) }
    val history: HistoryRepository by lazy { HistoryRepository(store) }

    val subsonicApi: SubsonicApi by lazy { SubsonicApi(http, logger) }

    init {
        refreshSubsonicConfig()
    }

    /** (Re)applies the Subsonic server configuration from shared settings. */
    fun refreshSubsonicConfig() {
        val s = settings.settings
        if (s.subsonicServerUrl.isNotBlank() && s.subsonicUsername.isNotBlank()) {
            subsonicApi.configure(
                SubsonicApi.ServerConfig(
                    baseUrl = s.subsonicServerUrl,
                    username = s.subsonicUsername,
                    password = s.subsonicPassword
                )
            )
        }
    }

    val extensions: ExtensionRuntime by lazy {
        ExtensionRuntime(
            factories = listOf<BuiltinExtensionFactory>(
                { createLocalExtension(library) },
                { createSubsonicExtension(subsonicApi, settings) }
            ),
            settings = settings,
            logger = logger
        )
    }

    val downloads: DownloadRepository by lazy {
        DownloadRepository(
            http = http,
            storage = storage,
            store = store,
            logger = logger,
            scope = scope,
            requestProvider = { track, maxBitrate ->
                val s = settings.settings
                if (subsonicApi.isConfigured) {
                    HttpRequest(
                        url = subsonicApi.streamUrl(track.id, maxBitrate, s.transcodeFormat),
                        requestTimeoutMs = 120_000
                    )
                } else null
            },
            quality = { settings.settings.downloadMaxBitrateKbps }
        )
    }

    val player: PlaybackController by lazy {
        PlaybackController(
            engine = engine,
            resolver = DefaultStreamResolver(extensions, downloads, library, logger),
            queue = QueueManager(TimeBasedQueueIdGenerator()),
            persister = dev.brahmkshatriya.echo.player.audio.KeyValuePlaybackPersister(store, json),
            logger = logger,
            scope = scope,
            onTrackStarted = { item ->
                runCatching {
                    history.record(
                        TrackRef(
                            extensionId = item.extensionId,
                            trackId = item.track.id,
                            title = item.track.title,
                            artist = item.track.artists.joinToString(", ") { it.name },
                            album = item.track.album?.title,
                            durationMs = item.track.duration
                        ),
                        0
                    )
                }
            }
        )
    }

    val search: SearchRepository by lazy { SearchRepository(extensions, library, logger) }

    val artworkLoader: dev.brahmkshatriya.echo.player.ui.ArtworkLoader by lazy {
        dev.brahmkshatriya.echo.player.ui.ArtworkLoader(http, logger)
    }

    /** Default extension to select on first launch. */
    fun defaultExtensionId(): String = LocalExtensionClient.ID

    /** Safe shut down of engine + http. */
    fun shutdown() {
        player.stop()
        engine.release()
        http.close()
    }
}
