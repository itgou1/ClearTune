package com.cleartune.app

import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.core.model.*
import com.cleartune.core.network.MusicTagClient
import com.cleartune.core.network.OpenSubsonicApiFactory
import com.cleartune.core.network.TagLibraryClient
import com.cleartune.core.network.LibraryRemoteDataSource
import com.cleartune.core.network.RemoteResult
import com.cleartune.core.datastore.CredentialsStore
import com.cleartune.core.datastore.MusicTagSettingsStore
import com.cleartune.core.network.NavidromeFileClient
import com.cleartune.app.metadata.normalizedTagLyrics
import java.io.File
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live acceptance. Only a dedicated copy under a QA directory can be written. */
class MusicTagLiveAcceptanceTest {
    @Test fun inspectOriginalReadPath() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mtQaReadOnly") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = requireNotNull(CredentialsStore(context).credentials.first())
        require(URI(credentials.baseUrl).host == URI(requireNotNull(args.getString("mtQaBase"))).host)
        val settings = MusicTagSettingsStore(context).read(accountStorageKey(credentials.baseUrl, credentials.username))
        val id = requireNotNull(args.getString("mtQaSongId"))
        val results = mutableListOf<JsonObject>()
        suspend fun <T> step(name: String, block: suspend () -> T): T {
            val start = System.nanoTime()
            try {
                val value = block()
                results += buildJsonObject { put("step", name); put("ok", true); put("ms", (System.nanoTime() - start) / 1_000_000) }
                return value
            } catch (error: Exception) {
                results += buildJsonObject {
                    put("step", name); put("ok", false); put("type", error.javaClass.name)
                    put("causeType", error.cause?.javaClass?.name.orEmpty())
                    put("stack", JsonArray(error.stackTrace.take(10).map { JsonPrimitive(it.toString()) }))
                }
                throw AssertionError("Read-only diagnostic failed at $name: ${error.javaClass.simpleName}")
            } finally {
                File(context.getExternalFilesDir(null), "music-tag-read-diagnostic.json").writeText(JsonArray(results).toString())
            }
        }
        repeat(2) { attempt ->
            val client = MusicTagClient(settings)
            step("$attempt login") { client.login() }
            val api = OpenSubsonicApiFactory().authorized(credentials)
            step("$attempt song") { TagLibraryClient(api).song(id) }
            val source = step("$attempt file-location") { NavidromeFileClient(credentials).songPath(id, settings.sourceRoot) }
            val path = MusicTagPath.resolve(source, settings.sourceRoot, settings.targetRoot)
            val before = step("$attempt read-tags") { client.read(path) }
            step("$attempt prepare-album-only") { client.prepareWrite(before, mapOf(MusicTagField.ALBUM to "Read-only diagnostic; never submitted")) }
            step("$attempt reread-tags") { client.read(path) }
        }
    }

    @Test fun verifyNavidromeCopy() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live acceptance is opt-in", args.getString("mtQaEnabled") == "true")
        val run = requireNotNull(args.getString("mtQaRun"))
        require(run.matches(Regex("cleartune-qa-[0-9]{8}-[a-f0-9]{6}")))
        val songId = requireNotNull(args.getString("mtQaSongId"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = requireNotNull(CredentialsStore(context).credentials.first())
        require(URI(credentials.baseUrl).host == URI(requireNotNull(args.getString("mtQaBase"))).host)
        val api = OpenSubsonicApiFactory().authorized(credentials)
        val library = TagLibraryClient(api)
        assertTrue("Navidrome must accept app scan request", library.startScan())
        var matched = false
        for (attempt in 0 until 20) {
            val song = library.song(songId)
            if (library.scanning() == false && song.album == "ClearTune acceptance $run" && song.year == 2001) {
                val lyrics = LibraryRemoteDataSource(api).lyrics(Song(songId, song.title, artistName = song.artist.orEmpty()))
                if (lyrics is RemoteResult.Success && normalizedTagLyrics(lyrics.value.lines.joinToString("\n") { it.text }) ==
                    listOf("ClearTune acceptance test", "Temporary MP3 copy only")) {
                    requireNotNull(song.coverArt)
                    matched = true
                    break
                }
            }
            delay(2000)
        }
        File(context.getExternalFilesDir(null), "music-tag-live-nav.json").writeText(buildJsonObject {
            put("run", run); put("songId", songId); put("scanAccepted", true)
            put("albumYearLyricsAndCoverIndexed", matched)
        }.toString())
        assertTrue("App must read updated album, year, lyrics and cover index", matched)
    }

    @Test fun verifyServerCopy() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live acceptance is opt-in", args.getString("mtQaEnabled") == "true")
        val run = requireNotNull(args.getString("mtQaRun"))
        require(run.matches(Regex("cleartune-qa-[0-9]{8}-[a-f0-9]{6}")))
        val base = requireNotNull(args.getString("mtQaBase"))
        val phase = requireNotNull(args.getString("mtQaPhase"))
        require(phase in setOf("year", "metadata", "album"))
        val client = MusicTagClient(MusicTagSettings(base, requireNotNull(args.getString("mtQaUser")),
            requireNotNull(args.getString("mtQaPassword")), allowHttp = true))
        client.login()
        val path = "/app/media/$run/acceptance-copy.mp3"
        val before = client.read(path)
        val changes = if (phase == "year") mapOf(MusicTagField.YEAR to "2001")
            else if (phase == "album") mapOf(MusicTagField.ALBUM to "ClearTune acceptance $run") else mapOf(
            MusicTagField.ALBUM to "ClearTune acceptance $run",
            MusicTagField.LYRICS to "[00:00.00]ClearTune acceptance test\n[00:01.00]Temporary MP3 copy only",
            MusicTagField.COVER to requireNotNull(args.getString("mtQaCover")))
        require(changes.any { (field, value) -> before.values[field] != value })
        client.write(before, changes)
        val after = client.read(path)
        val preserved = before.values.filterKeys { it !in changes }.all { (field, value) -> after.values[field] == value }
        val textMatches = changes.filterKeys { it != MusicTagField.COVER }.all { (field, value) -> after.values[field]?.trim() == value.trim() }
        val coverChanged = phase != "metadata" || (after.values[MusicTagField.COVER].orEmpty().isNotBlank() &&
            after.values[MusicTagField.COVER] != before.values[MusicTagField.COVER])
        val report = buildJsonObject {
            put("phase", phase); put("path", path); put("untouchedFieldsPreserved", preserved)
            put("changedTextReadBackMatches", textMatches); put("coverChanged", coverChanged)
            put("yearReadBack", after.values[MusicTagField.YEAR].orEmpty())
            put("albumReadBack", after.values[MusicTagField.ALBUM].orEmpty())
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "music-tag-live-$phase.json").writeText(report.toString())
        assertTrue("Unmodified fields must remain unchanged", preserved)
        assertTrue("Changed text must read back exactly", textMatches)
        assertTrue("Cover must change", coverChanged)
    }
}
