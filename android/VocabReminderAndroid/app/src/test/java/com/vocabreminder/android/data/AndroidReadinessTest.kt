package com.vocabreminder.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AndroidReadinessTest {
    private val now = Instant.parse("2026-07-13T12:00:00Z")

    @Test
    fun evaluatorKeepsAllFourOperationalStatesSeparate() {
        val items = AndroidReadinessEvaluator.evaluate(
            inputs(
                notificationsAllowed = false,
                batteryOptimizationExempt = false,
                periodicWorkScheduled = true,
                lastWorkerRunAt = now.minusSeconds(3 * 60 * 60),
                lastSyncSuccessAt = now.minusSeconds(600),
                lastSyncFailureAt = now.minusSeconds(60),
                lastSyncError = "Sync HTTP 503.",
                lastSyncRequestId = "server-request-123"
            )
        )

        assertEquals(AndroidReadinessKind.values().toSet(), items.map { it.kind }.toSet())
        assertTrue(items.all { it.level == AndroidReadinessLevel.ATTENTION })
        assertEquals("알림 차단", items.single { it.kind == AndroidReadinessKind.NOTIFICATIONS }.title)
        assertEquals("배터리 제한 가능", items.single { it.kind == AndroidReadinessKind.BATTERY }.title)
        assertEquals("WorkManager 지연", items.single { it.kind == AndroidReadinessKind.WORK_MANAGER }.title)
        assertEquals("마지막 동기화 실패", items.single { it.kind == AndroidReadinessKind.SYNC }.title)
        assertTrue(items.single { it.kind == AndroidReadinessKind.SYNC }.detail.contains("#server-r"))
    }

    @Test
    fun newlyRegisteredWorkerIsCheckingBeforeItBecomesDelayed() {
        val checking = AndroidReadinessEvaluator.evaluate(
            inputs(
                periodicWorkScheduled = true,
                firstLaunchAt = now.minusSeconds(30 * 60),
                lastWorkerRunAt = null
            )
        ).single { it.kind == AndroidReadinessKind.WORK_MANAGER }
        val delayed = AndroidReadinessEvaluator.evaluate(
            inputs(
                periodicWorkScheduled = true,
                firstLaunchAt = now.minusSeconds(3 * 60 * 60),
                lastWorkerRunAt = null
            )
        ).single { it.kind == AndroidReadinessKind.WORK_MANAGER }

        assertEquals(AndroidReadinessLevel.CHECKING, checking.level)
        assertEquals(AndroidReadinessLevel.ATTENTION, delayed.level)
    }

    @Test
    fun newerSyncSuccessDoesNotRemainAVisibleFailure() {
        val sync = AndroidReadinessEvaluator.evaluate(
            inputs(
                lastSyncFailureAt = now.minusSeconds(120),
                lastSyncSuccessAt = now.minusSeconds(30),
                lastSyncError = "stale error"
            )
        ).single { it.kind == AndroidReadinessKind.SYNC }

        assertEquals(AndroidReadinessLevel.READY, sync.level)
        assertTrue(sync.detail.startsWith("마지막 성공:"))
    }

    private fun inputs(
        notificationsAllowed: Boolean = true,
        batteryOptimizationExempt: Boolean = true,
        periodicWorkScheduled: Boolean? = true,
        firstLaunchAt: Instant = now.minusSeconds(24 * 60 * 60),
        lastWorkerRunAt: Instant? = now.minusSeconds(60),
        lastSyncSuccessAt: Instant? = null,
        lastSyncFailureAt: Instant? = null,
        lastSyncError: String = "",
        lastSyncRequestId: String = ""
    ): AndroidReadinessInputs {
        return AndroidReadinessInputs(
            notificationsAllowed = notificationsAllowed,
            batteryOptimizationExempt = batteryOptimizationExempt,
            periodicWorkScheduled = periodicWorkScheduled,
            firstLaunchAt = firstLaunchAt,
            lastWorkerRunAt = lastWorkerRunAt,
            lastSyncSuccessAt = lastSyncSuccessAt,
            lastSyncFailureAt = lastSyncFailureAt,
            lastSyncError = lastSyncError,
            lastSyncRequestId = lastSyncRequestId,
            now = now
        )
    }
}
