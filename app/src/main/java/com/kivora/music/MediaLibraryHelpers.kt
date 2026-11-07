package com.kivora.music

/**
 * Builds a key index while preserving the first item for duplicate keys.
 * This matches the previous linear firstOrNull lookup semantics.
 */
internal fun <T> firstItemByKey(
    items: Iterable<T>,
    keyOf: (T) -> String
): Map<String, T> {
    val index = HashMap<String, T>()
    for (item in items) {
        val key = keyOf(item)
        if (!index.containsKey(key)) index[key] = item
    }
    return index
}

/** Filters items by a trimmed, case-insensitive title query. */
internal fun <T> filterItemsByTitle(
    items: Iterable<T>,
    query: String,
    titleOf: (T) -> String
): List<T> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return items.toList()
    return items.filter { titleOf(it).contains(normalized, ignoreCase = true) }
}
