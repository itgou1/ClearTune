package com.cleartune.app.player

import com.cleartune.core.model.InternetRadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RadioStationRefreshTest {
    private fun station(id: String) = InternetRadioStation(id, "Station $id", "https://example.com/$id.mp3")

    @Test fun serverReorderingDoesNotDisturbVisibleList() {
        val current = listOf(station("1"), station("2"))
        assertSame(current, mergeRadioStations(current, current.reversed()))
    }

    @Test fun refreshUpdatesAddressesRemovesMissingStationsAndAppendsNewStations() {
        val current = listOf(station("1"), station("2"), station("3"))
        val updated = station("3").copy(streamUrl = "https://example.com/new.m3u8")
        val result = mergeRadioStations(current, listOf(station("4"), updated, station("1")))
        assertEquals(listOf("1", "3", "4"), result.map { it.id })
        assertSame(current.first(), result.first())
        assertEquals(updated, result[1])
        assertEquals(emptyList<InternetRadioStation>(), mergeRadioStations(result, emptyList()))
    }
}
