package com.itsmebillah.appinternetcontrol

import java.time.LocalTime

data class TwelveHourTime(val hour: Int, val minute: Int, val isPm: Boolean)

object ScheduleTimeConversion {
    fun fromPickerHourOfDay(hourOfDay: Int, minute: Int): LocalTime {
        require(hourOfDay in 0..23)
        require(minute in 0..59)
        val isPm = hourOfDay >= 12
        val hour12 = (hourOfDay % 12).let { if (it == 0) 12 else it }
        return fromTwelveHour(hour12, minute, isPm)
    }

    fun fromTwelveHour(hour: Int, minute: Int, isPm: Boolean): LocalTime {
        require(hour in 1..12)
        require(minute in 0..59)
        val hourOfDay = (hour % 12) + if (isPm) 12 else 0
        return LocalTime.of(hourOfDay, minute)
    }

    fun toTwelveHour(time: LocalTime): TwelveHourTime = TwelveHourTime(
        hour = (time.hour % 12).let { if (it == 0) 12 else it },
        minute = time.minute,
        isPm = time.hour >= 12,
    )
}
