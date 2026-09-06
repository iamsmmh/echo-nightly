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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The composition root of the shared player: builds every repository and the
 * [PlaybackController] exactly once, identically on Android and iOS.
 */
class AppGraph(
    val scope: CoroutineScope,
    val logger: EchoLogger,
    private val engine: PlayerEngine,
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

    private var sleepWatchJob: kotlinx.coroutines.Job? = null

    /** Crash-safe sleep timer; the snapshot is persisted next to settings. */
    val sleepTimer: dev.brahmkshatriya.echo.player.audiofx.SleepTimer by lazy {
        dev.brahmkshatriya.echo.player.audiofx.SleepTimer(
            scope = scope,
            onVolume = { factor -> player.setSleepVolumeFactor(factor) },
            onExpired = { player.onSleepExpired() }
        )
    }

    fun applyAudioFxSettings(s: dev.brahmkshatriya.echo.player.library.PlayerSettings) {
        runCatching {
            player.setAudioFxPreferences(
                crossfadeMs = s.crossfadeMs,
                replayGainMode = s.replayGainMode,
                preampDb = s.replayGainPreampDb,
                limiter = s.replayGainLimiter
            )
        }
    }

    /** Start a sleep timer and persist it so a crash cannot forget it. */
    fun startSleepTimer(minutes: Int) {
        settings.update { it.copy(sleepTimerMinutes = minutes) }
        if (minutes <= 0) {
            sleepTimer.cancel()
            store.putString(SLEEP_KEY, null)
            return
        }
        sleepTimer.start(minutes * 60_000L)
        store.putString(SLEEP_KEY, json.encodeToString(
            dev.brahmkshatriya.echo.player.audiofx.SleepTimerSnapshot.serializer(),
            sleepTimer.snapshot.value
        ))
        // clear persistence when the timer ends
        sleepWatchJob?.cancel()
        sleepWatchJob = scope.launch {
            sleepTimer.snapshot.collect { snap ->
                if (!snap.isActive) {
                    store.putString(SLEEP_KEY, null)
                    settings.update { it.copy(sleepTimerMinutes = 0) }
                }
            }
        }
    }

    private fun restoreSleepTimer() {
        val raw = store.getString(SLEEP_KEY) ?: return
        val snapshot = runCatching {
            json.decodeFromString(
                dev.brahmkshatriya.echo.player.audiofx.SleepTimerSnapshot.serializer(), raw
            )
        }.getOrNull() ?: return
        if (!snapshot.isActive) { store.putString(SLEEP_KEY, null); return }
        sleepTimer.restore(snapshot)
    }

    private companion object {
        const val SLEEP_KEY = "echo.sleep.timer"
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
            factories = listOf(
                BuiltinExtensionFactory { createLocalExtension(library) },
                BuiltinExtensionFactory { createSubsonicExtension(subsonicApi, settings) }
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
            quality = { settings.settings.downloadMaxBitrateKbps },
            requestForEntry = { entry, bitrate ->
                if (entry.extensionId == "subsonic" && subsonicApi.isConfigured) {
                    HttpRequest(subsonicApi.streamUrl(entry.trackId, bitrate, settings.settings.transcodeFormat), requestTimeoutMs = 120_000)
                } else {
                    val extension = extensions.extensionFor(entry.extensionId)
                    val client = extension?.instance?.value()?.getOrNull() as? dev.brahmkshatriya.echo.common.clients.TrackClient
                    val loaded = client?.loadTrack(entry.sourceTrack ?: dev.brahmkshatriya.echo.common.models.Track(entry.trackId, entry.title), true)
                    val server = loaded?.servers?.maxByOrNull { it.quality }
                    val media = server?.let { client?.loadStreamableMedia(it, true) } as? dev.brahmkshatriya.echo.common.models.Streamable.Media.Server
                    val source = media?.sources?.filterIsInstance<dev.brahmkshatriya.echo.common.models.Streamable.Source.Http>()?.firstOrNull()
                    source?.takeIf { it.decryption == null && !it.isLive }?.let { HttpRequest(it.request.url, headers = it.request.headers, requestTimeoutMs = 120_000) }
                }
            }
        )
    }

    val player: PlaybackController by lazy {
        PlaybackController(
            engine = engine,
            resolver = object : dev.brahmkshatriya.echo.player.audio.StreamResolver {
                override suspend fun resolve(item: dev.brahmkshatriya.echo.player.audio.QueueItem) = streamResolver.resolve(item)
            },
            queue = QueueManager(TimeBasedQueueIdGenerator()),
            persister = dev.brahmkshatriya.echo.player.audio.KeyValuePlaybackPersister(store, json),
            logger = logger,
            scope = scope,
            onTrackCompleted = { item, playedMs -> history.recordCompletion(refFor(item), playedMs) },
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

    private val streamResolver by lazy { DefaultStreamResolver(extensions, downloads, library, logger) }
    val recommendations by lazy { dev.brahmkshatriya.echo.player.domain.recommendations.RecommendationEngine(
        { dev.brahmkshatriya.echo.player.domain.nowEpochMs() }
    ) }
    val smartPlaylistEngine by lazy { dev.brahmkshatriya.echo.player.domain.playlists.SmartPlaylistEngine(
        { dev.brahmkshatriya.echo.player.domain.nowEpochMs() }
    ) }

    fun providerIdFor(item: dev.brahmkshatriya.echo.common.models.EchoMediaItem): String =
        item.extras[dev.brahmkshatriya.echo.player.domain.search.SearchProvenance.PROVIDER_ID]
            ?: if (item.extras.containsKey("localPath")) LocalExtensionClient.ID
            else extensions.activeExtensionId ?: LocalExtensionClient.ID

    private fun refFor(item: dev.brahmkshatriya.echo.player.audio.QueueItem) = TrackRef(
        item.extensionId, item.track.id, item.title, item.authors, item.track.album?.title, durationMs = item.track.duration
    )

    fun playRefs(refs: List<TrackRef>) {
        val items = refs.mapNotNull { ref ->
            val track = if (ref.extensionId == LocalExtensionClient.ID) library.find(ref.trackId)?.let(library::asTrack)
            else dev.brahmkshatriya.echo.common.models.Track(ref.trackId, ref.title,
                artists = listOf(dev.brahmkshatriya.echo.common.models.Artist("artist:${ref.artist}", ref.artist)),
                album = ref.album?.let { dev.brahmkshatriya.echo.common.models.Album("album:$it", it) }, duration = ref.durationMs)
            track?.let { dev.brahmkshatriya.echo.player.audio.QueueItem(dev.brahmkshatriya.echo.player.audio.newQueueId(), it, ref.extensionId) }
        }
        player.playItems(items)
    }

    /** One domain snapshot over existing stores; no second library or history system. */
    fun catalogSnapshot(): List<dev.brahmkshatriya.echo.player.library.LibrarySong> {
        val local = library.tracks.value.associateBy { "${LocalExtensionClient.ID}::${it.id}" }
        val favoriteKeys = favorites.favorites.value.map { it.key }.toSet()
        val downloaded = downloads.downloads.value.filterValues { it.status.state == dev.brahmkshatriya.echo.player.download.DownloadState.COMPLETED }
        val refs = local.values.map { TrackRef(LocalExtensionClient.ID, it.id, it.title, it.artist, it.album, durationMs = it.durationMs) } +
            favorites.favorites.value + history.history.value.map { it.ref } + downloaded.values.map {
                TrackRef(it.entry.extensionId, it.entry.trackId, it.entry.title, it.entry.artist, durationMs = it.entry.durationMs)
            }
        return refs.distinctBy { it.key }.map { ref ->
            val entry = local[ref.key]
            dev.brahmkshatriya.echo.player.library.LibrarySong(
                ref, addedAtMs = entry?.addedAtMs ?: downloaded[ref.key]?.entry?.createdAtMs ?: 0,
                downloaded = ref.key in downloaded || entry != null, favorite = ref.key in favoriteKeys,
                stats = history.stats.value[ref.key] ?: dev.brahmkshatriya.echo.player.library.ListeningStats(),
                genres = setOfNotNull(entry?.genre), albumArtist = entry?.albumArtist ?: ref.artist,
                albumOrder = entry?.trackNumber?.toLong()
            )
        }
    }

    /** Default extension to select on first launch. */
    fun defaultExtensionId(): String = LocalExtensionClient.ID

    /** Safe shut down of engine + http. */
    fun shutdown() {
        sleepWatchJob?.cancel()
        sleepTimer.cancel()
        player.release()
        downloads.close()
        engine.release()
        http.close()
    }

    init {
        refreshSubsonicConfig()
        // Audio FX preferences (Phase 5) feed the shared controller.
        settings.addListener { applyAudioFxSettings(it) }
        player.restoreSession()
        restoreSleepTimer()
        scope.launch { downloads.checkHealth() }
    }

}
