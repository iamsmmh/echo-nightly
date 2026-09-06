package dev.brahmkshatriya.echo.player.platform

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String): Long
    fun putLong(key: String, value: Long)
    fun getBoolean(key: String): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun contains(key: String): Boolean
}

/** Simple in-memory [KeyValueStore] used by tests and previews. */
class InMemoryKeyValueStore : KeyValueStore {
    private val strings = mutableMapOf<String, String>()
    private val longs = mutableMapOf<String, Long>()
    private val booleans = mutableMapOf<String, Boolean>()

    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }

    override fun getLong(key: String): Long = longs[key] ?: 0L
    override fun putLong(key: String, value: Long) {
        longs[key] = value
    }

    override fun getBoolean(key: String): Boolean = booleans[key] ?: false
    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }

    override fun remove(key: String) {
        strings.remove(key); longs.remove(key); booleans.remove(key)
    }

    override fun contains(key: String): Boolean =
        key in strings || key in longs || key in booleans
}

/** Creates a persistent named key-value store on the current platform. */
expect fun createKeyValueStore(name: String): KeyValueStore

/**
 * Platform file storage for music, downloads and cache. Android uses app
 * external files storage; iOS uses Application Support.
 */
interface MusicStorage {
    /** Directory holding the imported local music library. */
    val musicDir: EchoFile

    /** Directory holding downloaded offline tracks. */
    val downloadsDir: EchoFile

    /** Directory holding cache data (artwork, partial downloads). */
    val cacheDir: EchoFile

    /**
     * Copies a file from [sourcePath] (e.g. a document-picker URL) into [dir].
     *
     * @return the created file.
     */
    fun copyInto(sourcePath: String, dir: EchoFile, fileName: String): EchoFile
}

/** Creates the platform music storage. Android requires [initAndroidContext] first. */
expect fun createMusicStorage(): MusicStorage

/**
 * Metadata of a local audio file, read by the platform metadata reader.
 */
data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val durationMs: Long? = null,
    val artworkBytes: ByteArray? = null,
    val mimeType: String? = null
) {
    val artworkIsPng: Boolean get() = artworkBytes != null &&
        artworkBytes.size > 4 && artworkBytes[0] == 0x89.toByte() && artworkBytes[1] == 0x50.toByte()
}

/** Reads [AudioMetadata] from a local audio file path. */
interface MetadataReader {
    fun read(path: String): AudioMetadata
}

/** Creates the platform metadata reader (MediaMetadataRetriever / AVAsset). */
expect fun createMetadataReader(): MetadataReader

/**
 * Supported audio formats per platform, used to warn users before attempting
 * playback of files a platform cannot decode.
 */
data class AudioCapabilities(
    val mp3: Boolean,
    val aacM4a: Boolean,
    val wav: Boolean,
    val flac: Boolean,
    val ogg: Boolean,
    val opus: Boolean
) {
    fun supports(mimeType: String?): Boolean {
        if (mimeType == null) return true
        return when (mimeType.lowercase().substringAfter('/')) {
            "mpeg", "mp3" -> mp3
            "aac", "mp4", "m4a", "x-m4a" -> aacM4a
            "wav", "x-wav", "wave" -> wav
            "flac", "x-flac" -> flac
            "ogg", "vorbis" -> ogg
            "opus" -> opus
            else -> true
        }
    }
}

/** Returns the platform's audio decoding capabilities. */
expect fun audioCapabilities(): AudioCapabilities
