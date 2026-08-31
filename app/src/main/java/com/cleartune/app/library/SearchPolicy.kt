package com.cleartune.app.library

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import com.cleartune.core.database.SearchDocumentEntity
import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.Playlist
import com.cleartune.core.model.Song
import com.cleartune.core.network.SearchResults
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal object SearchDocumentType {
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val SONG = "song"
    const val PLAYLIST = "playlist"
}

internal const val SEARCH_INDEX_FORMAT_MARKER = "ctsearchformatmixedpinyin3"

internal data class SearchQueryPlan(
    val original: String,
    val normalized: String,
    val variants: List<String>,
    val matchQuery: String,
)

private val searchSynonyms = mapOf(
    "摇滚" to "rock",
    "流行" to "pop",
    "古典" to "classical",
    "爵士" to "jazz",
    "电子" to "electronic",
    "民谣" to "folk",
    "说唱" to "rap hiphop",
    "嘻哈" to "hiphop rap",
    "轻音乐" to "instrumental easy listening",
)

internal fun buildSearchQueryPlan(query: String): SearchQueryPlan {
    val normalized = normalizeSearchText(query)
    if (normalized.isBlank()) return SearchQueryPlan(query, "", emptyList(), "")
    val reverseSynonym = searchSynonyms.entries.firstOrNull {
        normalizeSearchText(it.value) == normalized
    }?.key
    val variants = buildList {
        add(normalized)
        searchSynonyms[normalized]?.let(::add)
        reverseSynonym?.let(::add)
    }.map(::normalizeSearchText).filter(String::isNotBlank).distinct()
    val clauses = variants.mapNotNull { variant ->
        val tokens = searchTokens(variant)
        tokens.takeIf(List<String>::isNotEmpty)?.joinToString(" AND ") { token -> "$token*" }
    }
    return SearchQueryPlan(
        original = query,
        normalized = normalized,
        variants = variants,
        matchQuery = clauses.joinToString(" OR ") { "($it)" },
    )
}

internal fun searchDocument(artist: Artist): SearchDocumentEntity = searchDocument(
    type = SearchDocumentType.ARTIST,
    id = artist.id,
    title = artist.name,
    subtitle = "",
    keywords = "",
)

internal fun searchDocument(album: Album): SearchDocumentEntity = searchDocument(
    type = SearchDocumentType.ALBUM,
    id = album.id,
    title = album.name,
    subtitle = album.artistName,
    keywords = album.year?.toString().orEmpty(),
)

internal fun searchDocument(song: Song): SearchDocumentEntity = searchDocument(
    type = SearchDocumentType.SONG,
    id = song.id,
    title = song.title,
    subtitle = listOf(song.artistName, song.albumName).joinToString(" "),
    keywords = normalizeGenreLabel(song.genre).orEmpty(),
)

internal fun SearchResults.resultCount(category: SearchCategory): Int = when (category) {
    SearchCategory.ARTISTS -> artists.size
    SearchCategory.ALBUMS -> albums.size
    SearchCategory.SONGS -> songs.size
    SearchCategory.PLAYLISTS -> playlists.size
}

internal fun searchDocument(playlist: Playlist): SearchDocumentEntity = searchDocument(
    type = SearchDocumentType.PLAYLIST,
    id = playlist.id,
    title = playlist.name,
    subtitle = "",
    keywords = "",
)

private fun searchDocument(
    type: String,
    id: String,
    title: String,
    subtitle: String,
    keywords: String,
): SearchDocumentEntity {
    val sourceParts = listOf(title, subtitle, keywords)
        .filter(String::isNotBlank)
    val source = sourceParts.joinToString(" ")
    val pinyinParts = sourceParts.map { part ->
        normalizeSearchText(SearchTransliterator.transliterate(part))
    }.filter(String::isNotBlank)
    val pinyin = (pinyinParts.flatMap(::pinyinSearchAliases) + SEARCH_INDEX_FORMAT_MARKER)
        .distinct()
        .joinToString(" ")
    val initials = pinyinParts.map(::pinyinInitials)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" ")
    return SearchDocumentEntity(
        entityType = type,
        entityId = id,
        title = title,
        subtitle = subtitle,
        keywords = "$source ${hanCharacterTokens(source)}",
        pinyin = pinyin,
        initials = initials,
    )
}

internal fun pinyinSearchAliases(words: String): List<String> {
    val syllables = words.split(' ').filter(String::isNotBlank)
    if (syllables.isEmpty()) return emptyList()
    val leadingInitialCount = syllables.takeWhile { it.length == 1 }.size
    val searchableGroups = buildList {
        add(syllables)
        if (leadingInitialCount >= 2 && leadingInitialCount < syllables.size) {
            add(syllables.drop(leadingInitialCount))
        }
    }
    return searchableGroups.flatMap(::pinyinAliasesForSyllables)
        .filter(String::isNotBlank)
        .distinct()
}

private fun pinyinAliasesForSyllables(syllables: List<String>): List<String> {
    val compact = syllables.joinToString("")
    val initials = syllables.mapNotNull { it.firstOrNull() }.joinToString("")
    return buildList {
        add(syllables.joinToString(" "))
        add(compact)
        add(initials)
        // Common mobile input style: full first syllable(s) plus initials for the remainder,
        // for example 邓紫棋 -> dengzq and 周杰伦 -> zhoujl.
        for (fullSyllableCount in 1 until syllables.size) {
            add(
                syllables.take(fullSyllableCount).joinToString("") +
                    syllables.drop(fullSyllableCount).mapNotNull { it.firstOrNull() }.joinToString(""),
            )
        }
    }
}

private fun pinyinInitials(words: String): String = words.split(' ')
    .mapNotNull { word -> word.firstOrNull()?.takeIf(Char::isLetterOrDigit) }
    .joinToString("")

private object SearchTransliterator {
    fun transliterate(value: String): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Api29SearchTransliterator.transliterate(value)
    } else {
        value
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private object Api29SearchTransliterator {
    private val transliterator: Transliterator? by lazy {
        runCatching {
            Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower()")
        }.getOrNull()
    }

    fun transliterate(value: String): String = synchronized(this) {
        transliterator?.transliterate(value) ?: value
    }
}

internal fun rankSearchDocuments(
    documents: List<SearchDocumentEntity>,
    plan: SearchQueryPlan,
): List<SearchDocumentEntity> = documents
    .map { document ->
        RankedSearchDocument(
            document = document,
            score = searchScore(document, plan),
            normalizedTitle = normalizeSearchText(document.title),
        )
    }
    .sortedWith(
        compareByDescending<RankedSearchDocument> { it.score }
            .thenBy(RankedSearchDocument::normalizedTitle)
            .thenBy { it.document.entityId },
    )
    .map(RankedSearchDocument::document)

private data class RankedSearchDocument(
    val document: SearchDocumentEntity,
    val score: Int,
    val normalizedTitle: String,
)

private fun searchScore(document: SearchDocumentEntity, plan: SearchQueryPlan): Int {
    val title = normalizeSearchText(document.title)
    val subtitle = normalizeSearchText(document.subtitle)
    val keywords = normalizeSearchText(document.keywords)
    val pinyinTokens = normalizeSearchText(document.pinyin).split(' ').filter(String::isNotBlank)
    val initialTokens = normalizeSearchText(document.initials).split(' ').filter(String::isNotBlank)
    return plan.variants.maxOfOrNull { variant ->
        val compact = variant.replace(" ", "")
        when {
            title == variant -> 1_000
            title.startsWith(variant) -> 900
            title.contains(variant) -> 800
            pinyinTokens.any { it == compact } -> 760
            initialTokens.any { it == compact } -> 740
            pinyinTokens.any { it.startsWith(compact) } -> 700
            initialTokens.any { it.startsWith(compact) } -> 680
            subtitle == variant -> 620
            subtitle.startsWith(variant) -> 560
            subtitle.contains(variant) -> 500
            pinyinTokens.any { it.contains(compact) } -> 440
            initialTokens.any { it.contains(compact) } -> 420
            keywords.contains(variant) -> 300
            else -> 0
        }
    } ?: 0
}

internal fun closestSearchSuggestions(
    query: String,
    documents: List<SearchDocumentEntity>,
    limit: Int = 3,
): List<String> {
    val normalizedQuery = normalizeSearchText(query).replace(" ", "")
    if (normalizedQuery.length < 2) return emptyList()
    val threshold = min(3, max(1, normalizedQuery.length / 3))
    return documents.asSequence()
        .map(SearchDocumentEntity::title)
        .distinctBy(::normalizeSearchText)
        .map { candidate ->
            val normalizedCandidate = normalizeSearchText(candidate).replace(" ", "")
            candidate to levenshteinDistance(normalizedQuery, normalizedCandidate, threshold)
        }
        .filter { (_, distance) -> distance in 1..threshold }
        .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })
        .map(Pair<String, Int>::first)
        .take(limit)
        .toList()
}

internal fun shouldSearchServer(
    localResultCount: Int,
    lastLibrarySyncEpochMs: Long,
    forced: Boolean,
    now: Long = System.currentTimeMillis(),
): Boolean = forced ||
    localResultCount < MIN_LOCAL_RESULTS_BEFORE_REMOTE ||
    lastLibrarySyncEpochMs <= 0L ||
    now - lastLibrarySyncEpochMs >= SERVER_SEARCH_STALE_AFTER_MS

internal fun mergeFreshSearchResults(first: SearchResults, fresh: SearchResults): SearchResults = SearchResults(
    artists = mergeById(first.artists, fresh.artists, Artist::id) { local, remote ->
        remote.copy(starredAt = local.starredAt)
    },
    albums = mergeById(first.albums, fresh.albums, Album::id) { local, remote ->
        remote.copy(starredAt = local.starredAt)
    },
    songs = mergeById(first.songs, fresh.songs, Song::id) { local, remote ->
        remote.copy(
            playCount = max(local.playCount, remote.playCount),
            lastPlayedAt = listOfNotNull(local.lastPlayedAt, remote.lastPlayedAt).maxOrNull(),
            starredAt = local.starredAt,
        )
    },
    playlists = mergeById(first.playlists, fresh.playlists, Playlist::id) { local, remote ->
        remote.copy(changedAt = listOfNotNull(local.changedAt, remote.changedAt).maxOrNull())
    },
)

private fun <T> mergeById(
    local: List<T>,
    remote: List<T>,
    id: (T) -> String,
    merge: (T, T) -> T,
): List<T> {
    val remoteById = remote.associateBy(id).toMutableMap()
    return buildList {
        local.forEach { localItem ->
            val remoteItem = remoteById.remove(id(localItem))
            add(if (remoteItem == null) localItem else merge(localItem, remoteItem))
        }
        addAll(remote.filter { id(it) in remoteById })
    }
}

private val combiningMarksPattern = Regex("\\p{M}+")
private val nonSearchCharacterPattern = Regex("[^\\p{L}\\p{N}]+")
private val repeatedWhitespacePattern = Regex("\\s+")

internal fun normalizeSearchText(value: String): String = Normalizer.normalize(
    value.lowercase(Locale.ROOT),
    Normalizer.Form.NFD,
).replace(combiningMarksPattern, "")
    .replace(nonSearchCharacterPattern, " ")
    .trim()
    .replace(repeatedWhitespacePattern, " ")

private fun searchTokens(value: String): List<String> = buildList {
    val latin = StringBuilder()
    fun flushLatin() {
        if (latin.isNotEmpty()) {
            add(latin.toString())
            latin.clear()
        }
    }
    value.forEach { character ->
        when {
            character.isHanCharacter() -> {
                flushLatin()
                add(character.toString())
            }
            character.isLetterOrDigit() -> latin.append(character)
            else -> flushLatin()
        }
    }
    flushLatin()
}.filter(String::isNotBlank)

private fun hanCharacterTokens(value: String): String = value.asSequence()
    .filter(Char::isHanCharacter)
    .joinToString(" ")

private fun Char.isHanCharacter(): Boolean =
    Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN

private fun levenshteinDistance(first: String, second: String, stopAfter: Int): Int {
    if (kotlin.math.abs(first.length - second.length) > stopAfter) return stopAfter + 1
    var previous = IntArray(second.length + 1) { it }
    for (firstIndex in first.indices) {
        val current = IntArray(second.length + 1)
        current[0] = firstIndex + 1
        var rowMinimum = current[0]
        for (secondIndex in second.indices) {
            val substitution = previous[secondIndex] +
                if (first[firstIndex] == second[secondIndex]) 0 else 1
            current[secondIndex + 1] = minOf(
                current[secondIndex] + 1,
                previous[secondIndex + 1] + 1,
                substitution,
            )
            rowMinimum = min(rowMinimum, current[secondIndex + 1])
        }
        if (rowMinimum > stopAfter) return stopAfter + 1
        previous = current
    }
    return previous[second.length]
}

private const val MIN_LOCAL_RESULTS_BEFORE_REMOTE = 5
private const val SERVER_SEARCH_STALE_AFTER_MS = 24 * 60 * 60 * 1_000L
