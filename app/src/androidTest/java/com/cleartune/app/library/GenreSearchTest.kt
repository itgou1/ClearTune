package com.cleartune.app.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartune.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenreSearchTest {
    @Test
    fun chineseGenreFindsChineseEnglishAndSubgenreTags() {
        SQLiteDatabase.create(null).use { database ->
            database.execSQL(
                "CREATE VIRTUAL TABLE search_documents USING fts4(" +
                    "entityType, entityId, title, subtitle, keywords, pinyin, initials, tokenize=unicode61)",
            )
            listOf(
                Song("chinese", "甲", genre = "流行"),
                Song("english", "乙", genre = "Pop"),
                Song("subgenre", "丙", genre = "Chinese Pop"),
                Song("rock", "丁", genre = "Rock"),
                Song("full-title", "晴天", artistName = "周杰伦"),
                Song("same-artist", "夜曲", artistName = "周杰伦"),
                Song("same-title", "晴天", artistName = "其他歌手"),
            ).forEach { song ->
                val document = searchDocument(song)
                database.insertOrThrow("search_documents", null, ContentValues().apply {
                    put("entityType", document.entityType)
                    put("entityId", document.entityId)
                    put("title", document.title)
                    put("subtitle", document.subtitle)
                    put("keywords", document.keywords)
                    put("pinyin", document.pinyin)
                    put("initials", document.initials)
                })
            }
            val expectations = mapOf(
                "流行" to setOf("chinese", "english", "subgenre"),
                "pop" to setOf("chinese", "english", "subgenre"),
                "摇滚" to setOf("rock"),
                "rock" to setOf("rock"),
                "周杰伦 晴天" to setOf("full-title"),
                "不存在的流派" to emptySet(),
            )
            for ((query, expectedIds) in expectations) {
                val plan = buildSearchQueryPlan(query)
                val ids = plan.matchQueries.flatMap { matchQuery ->
                    database.rawQuery(
                        "SELECT entityId FROM search_documents WHERE search_documents MATCH ?",
                        arrayOf(matchQuery),
                    ).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    }
                }.toSet()
                assertEquals(query, expectedIds, ids)
            }
        }
    }
}
