package com.itsmebillah.appinternetcontrol

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class TemporaryBlock(
    val id: String = UUID.randomUUID().toString(),
    val packageNames: Set<String>,
    val startedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(packageNames.isNotEmpty())
        require(expiresAt > startedAt)
    }
}

object TemporaryBlockEvaluator {
    fun activePackageNames(blocks: List<TemporaryBlock>, now: Instant): Set<String> =
        blocks.asSequence()
            .filter { now >= it.startedAt && now < it.expiresAt }
            .flatMap { it.packageNames.asSequence() }
            .toSet()

    fun nextExpiry(blocks: List<TemporaryBlock>, now: Instant): Instant? =
        blocks.asSequence()
            .filter { it.expiresAt > now && it.startedAt <= now }
            .map { it.expiresAt }
            .minOrNull()
}

object EffectiveBlockResolver {
    fun resolve(
        manualBlockedPackages: Set<String>,
        activeFocusMode: FocusMode? = null,
        schedules: List<BlockSchedule>,
        temporaryBlocks: List<TemporaryBlock>,
        now: Instant,
        zone: ZoneId,
    ): Set<String> = ScheduleEvaluator.effectiveBlockedPackages(
        manualBlockedPackages = manualBlockedPackages + activeFocusMode?.packageNames.orEmpty(),
        schedules = schedules,
        now = now,
        zone = zone,
    ) + TemporaryBlockEvaluator.activePackageNames(temporaryBlocks, now)
}
