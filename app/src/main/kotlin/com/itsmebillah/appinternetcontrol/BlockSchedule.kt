package com.itsmebillah.appinternetcontrol

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

enum class ScheduleAction { BLOCK, ALLOW }

data class BlockSchedule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val packageNames: Set<String>,
    val action: ScheduleAction,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val oneTimeDate: LocalDate? = null,
    val enabled: Boolean = true,
) {
    init {
        require(name.isNotBlank())
        require(packageNames.isNotEmpty())
        require(oneTimeDate != null || weekdays.isNotEmpty())
    }
}

object ScheduleEvaluator {
    fun isActive(schedule: BlockSchedule, now: Instant, zone: ZoneId): Boolean {
        if (!schedule.enabled) return false
        val localDate = now.atZone(zone).toLocalDate()
        val candidateDates = if (schedule.oneTimeDate != null) {
            listOf(schedule.oneTimeDate)
        } else {
            listOf(localDate.minusDays(1), localDate)
        }
        return candidateDates.any { date ->
            if (schedule.oneTimeDate != null && date != schedule.oneTimeDate) return@any false
            if (schedule.oneTimeDate == null && date.dayOfWeek !in schedule.weekdays) return@any false
            val start = date.atTime(schedule.startTime).atZone(zone).toInstant()
            val endDate = if (schedule.endTime <= schedule.startTime) date.plusDays(1) else date
            val end = endDate.atTime(schedule.endTime).atZone(zone).toInstant()
            now >= start && now < end
        }
    }

    fun effectiveBlockedPackages(
        manualBlockedPackages: Set<String>,
        schedules: List<BlockSchedule>,
        now: Instant,
        zone: ZoneId,
    ): Set<String> {
        val decisions = mutableMapOf<String, MutableSet<ScheduleAction>>()
        schedules.filter { isActive(it, now, zone) }.forEach { schedule ->
            schedule.packageNames.forEach { packageName ->
                decisions.getOrPut(packageName) { mutableSetOf() }.add(schedule.action)
            }
        }

        return manualBlockedPackages.toMutableSet().apply {
            decisions.forEach { (packageName, actions) ->
                if (ScheduleAction.BLOCK in actions) add(packageName) else remove(packageName)
            }
        }
    }

    fun nextBoundary(schedules: List<BlockSchedule>, now: Instant, zone: ZoneId): Instant? {
        val firstDate = now.atZone(zone).toLocalDate().minusDays(1)
        val lastDate = firstDate.plusDays(9)
        return schedules.asSequence()
            .filter { it.enabled }
            .flatMap { schedule ->
                val dates = schedule.oneTimeDate?.let(::listOf) ?: generateSequence(firstDate) { date ->
                    if (date < lastDate) date.plusDays(1) else null
                }.toList()
                dates.asSequence()
                    .filter { date -> schedule.oneTimeDate != null || date.dayOfWeek in schedule.weekdays }
                    .flatMap { date ->
                        val endDate = if (schedule.endTime <= schedule.startTime) date.plusDays(1) else date
                        sequenceOf(
                            date.atTime(schedule.startTime).atZone(zone).toInstant(),
                            endDate.atTime(schedule.endTime).atZone(zone).toInstant(),
                        )
                    }
            }
            .filter { it > now }
            .minOrNull()
    }

    fun nextBoundaryLabel(schedules: List<BlockSchedule>, now: Instant, zone: ZoneId): ZonedDateTime? =
        nextBoundary(schedules, now, zone)?.atZone(zone)
}
