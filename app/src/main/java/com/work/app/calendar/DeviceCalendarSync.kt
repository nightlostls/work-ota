package com.work.app.calendar

import android.content.Context
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId

data class SyncedCalendarEvent(
    val title: String,
    val allDay: Boolean
)

object DeviceCalendarSync {
    fun getEventsForDate(context: Context, date: LocalDate): List<SyncedCalendarEvent> {
        val zone = ZoneId.systemDefault()
        val startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()

        val result = mutableListOf<SyncedCalendarEvent>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIdx) ?: continue
                    val allDay = cursor.getInt(allDayIdx) == 1
                    result.add(SyncedCalendarEvent(title, allDay))
                }
            }
        }
        return result
    }
}
