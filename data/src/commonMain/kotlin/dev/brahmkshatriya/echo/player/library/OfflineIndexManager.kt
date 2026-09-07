package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.common.models.EchoFile

/** Incremental in-memory search index for large offline libraries. */
class OfflineIndexManager<T>(
    private val idOf: (T) -> String,
    private val termsOf: (T) -> Iterable<String>,
    private val pathOf: ((T) -> String?)? = null
) {
    private val records = linkedMapOf<String, T>()
    private val tokensById = mutableMapOf<String, Set<String>>()
    private val postings = mutableMapOf<String, MutableSet<String>>()

    val size: Int get() = records.size

    fun rebuild(items: Iterable<T>) {
        records.clear(); tokensById.clear(); postings.clear()
        items.forEach(::upsert)
    }

    fun upsert(item: T) {
        val id = idOf(item)
        remove(id)
        records[id] = item
        val tokens = termsOf(item).flatMap(::tokenize).toSet()
        tokensById[id] = tokens
        tokens.forEach { postings.getOrPut(it) { linkedSetOf() }.add(id) }
    }

    fun remove(id: String): T? {
        val removed = records.remove(id) ?: return null
        tokensById.remove(id).orEmpty().forEach { token ->
            postings[token]?.let { ids ->
                ids.remove(id)
                if (ids.isEmpty()) postings.remove(token)
            }
        }
        return removed
    }

    fun search(query: String): List<T> {
        val words = tokenize(query)
        if (words.isEmpty()) return records.values.toList()
        val candidates = words.map { word ->
            postings.entries.asSequence().filter { (token, _) -> token.startsWith(word) }
                .flatMap { it.value.asSequence() }.toSet()
        }.reduceOrNull(Set<String>::intersect).orEmpty()
        return records.values.filter { idOf(it) in candidates }
    }

    /** Removes entries whose local file disappeared and returns their stable ids. */
    fun removeOrphans(exists: (String) -> Boolean = { EchoFile(it).exists() }): List<String> {
        val path = pathOf ?: return emptyList()
        val orphanIds = records.values.mapNotNull { item ->
            val itemPath = path(item) ?: return@mapNotNull null
            idOf(item).takeUnless { exists(itemPath) }
        }
        orphanIds.forEach(::remove)
        return orphanIds
    }

    private fun tokenize(value: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        value.lowercase().forEach { character ->
            if (character.isLetterOrDigit()) token.append(character)
            else if (token.isNotEmpty()) {
                result += token.toString()
                token.clear()
            }
        }
        if (token.isNotEmpty()) result += token.toString()
        return result
    }
}
