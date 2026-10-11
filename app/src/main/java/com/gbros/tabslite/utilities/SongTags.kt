package com.gbros.tabslite.utilities

/** The tags of a `tags` header or stored tag list (`táborák, Vánoční, easy`): trimmed, blank and repeated ones (see [tagKey]) dropped */
fun parseTags(value: String): List<String> =
    value.split(',').map { it.trim() }.filter { tagKey(it).isNotEmpty() }.distinctBy { tagKey(it) }

/** What spellings of one tag have in common, ignoring case, diacritics and punctuation: `Táborák` and `taborak` are one tag */
fun tagKey(tag: String): String = foldForSearch(tag)

/** Whether the stored tag list [tags] has every tag of [wantedKeys] (see [tagKey]) */
fun hasAllTags(tags: String, wantedKeys: Collection<String>): Boolean {
    if (wantedKeys.isEmpty()) return true
    val keys = parseTags(tags).map { tagKey(it) }.toSet()
    return wantedKeys.all { it in keys }
}

/** Every tag of the stored tag lists [tagLists] once, as first spelled, sorted ignoring case and diacritics */
fun allTags(tagLists: Iterable<String>): List<String> =
    tagLists.flatMap { parseTags(it) }.distinctBy { tagKey(it) }.sortedBy { tagKey(it) }
