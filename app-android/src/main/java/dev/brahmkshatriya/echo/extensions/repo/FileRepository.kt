package dev.brahmkshatriya.echo.extensions.repo

import android.content.Context
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Metadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.WeakHashMap

class FileRepository(
    private val folder: File,
    private val parser: ExtensionParser,
    private val fileIgnoreFlow: Flow<File?>
) : ExtensionRepository {

    private val map =
        WeakHashMap<String, Pair<String, Result<Pair<Metadata, Lazy<ExtensionClient>>>>>()
    private val mutex = Mutex()

    private var toIgnoreFile: File? = null
    override val flow = channelFlow {
        send(loadExtensions())
        fileIgnoreFlow.collectLatest {
            toIgnoreFile = it
            send(loadExtensions())
        }
    }.flowOn(Dispatchers.IO)

    private fun loadAllApks() = folder.run {
        runCatching { setReadOnly() }
        // Phase 1 (stability): a removed folder must not crash the loader and
        // truncated/non-zip files must never reach the parser (they produced
        // the "extension loading crashes" of the past).
        val apks = (listFiles() ?: emptyArray()).filter {
            it != toIgnoreFile && it.extension == "apk" &&
                it.isFile && it.length() > 0 &&
                dev.brahmkshatriya.echo.utils.FileIntegrity.hasZipHeader(it)
        }
        apks.onEach { runCatching { it.setWritable(false) } }
    }

    override suspend fun loadExtensions() = mutex.withLock {
        parser.getAllDynamically(ImportType.File, map, loadAllApks())
    }

    constructor(
        context: Context, parser: ExtensionParser, fileIgnoreFlow: Flow<File?>
    ) : this(context.getExtensionsFileDir(), parser, fileIgnoreFlow)

    companion object {
        fun Context.getExtensionsFileDir() = File(filesDir, "extensions").apply { mkdirs() }
    }
}