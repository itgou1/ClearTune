package com.cleartune.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun formatLibrarySyncTime(
    epochMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val synced = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
    val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
    val sameDay = synced.get(Calendar.ERA) == now.get(Calendar.ERA) &&
        synced.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        synced.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "'今天' HH:mm"
        synced.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "M月d日 HH:mm"
        else -> "yyyy年M月d日 HH:mm"
    }
    return SimpleDateFormat(pattern, Locale.SIMPLIFIED_CHINESE).apply {
        this.timeZone = timeZone
    }.format(Date(epochMillis))
}
