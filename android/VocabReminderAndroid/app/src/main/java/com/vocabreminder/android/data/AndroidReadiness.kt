package com.vocabreminder.android.data

import java.time.Duration
import java.time.Instant

data class AndroidOnboardingState(
    val firstLaunchAt: Instant,
    val captureGuideCompletedAt: Instant? = null
)

enum class AndroidReadinessKind {
    NOTIFICATIONS,
    BATTERY,
    WORK_MANAGER,
    SYNC
}

enum class AndroidReadinessLevel {
    READY,
    CHECKING,
    ATTENTION
}

data class AndroidReadinessInputs(
    val notificationsAllowed: Boolean,
    val batteryOptimizationExempt: Boolean,
    val periodicWorkScheduled: Boolean?,
    val firstLaunchAt: Instant,
    val lastWorkerRunAt: Instant?,
    val lastSyncSuccessAt: Instant?,
    val lastSyncFailureAt: Instant?,
    val lastSyncError: String,
    val lastSyncRequestId: String,
    val now: Instant
)

data class AndroidReadinessItem(
    val kind: AndroidReadinessKind,
    val level: AndroidReadinessLevel,
    val title: String,
    val detail: String
)

object AndroidReadinessEvaluator {
    val workerDelayThreshold: Duration = Duration.ofHours(2)

    fun evaluate(inputs: AndroidReadinessInputs): List<AndroidReadinessItem> {
        return listOf(
            notificationItem(inputs.notificationsAllowed),
            batteryItem(inputs.batteryOptimizationExempt),
            workerItem(inputs),
            syncItem(inputs)
        )
    }

    private fun notificationItem(allowed: Boolean): AndroidReadinessItem {
        return if (allowed) {
            AndroidReadinessItem(
                AndroidReadinessKind.NOTIFICATIONS,
                AndroidReadinessLevel.READY,
                "알림",
                "복습 알림을 표시할 수 있습니다."
            )
        } else {
            AndroidReadinessItem(
                AndroidReadinessKind.NOTIFICATIONS,
                AndroidReadinessLevel.ATTENTION,
                "알림 차단",
                "알림 권한 또는 앱 알림이 꺼져 있어 복습 알림이 표시되지 않습니다."
            )
        }
    }

    private fun batteryItem(exempt: Boolean): AndroidReadinessItem {
        return if (exempt) {
            AndroidReadinessItem(
                AndroidReadinessKind.BATTERY,
                AndroidReadinessLevel.READY,
                "배터리 제한",
                "이 앱은 배터리 최적화 예외로 허용되어 있습니다."
            )
        } else {
            AndroidReadinessItem(
                AndroidReadinessKind.BATTERY,
                AndroidReadinessLevel.ATTENTION,
                "배터리 제한 가능",
                "제조사 절전 정책에 따라 백그라운드 복습이 늦어질 수 있습니다."
            )
        }
    }

    private fun workerItem(inputs: AndroidReadinessInputs): AndroidReadinessItem {
        val scheduled = inputs.periodicWorkScheduled
        if (scheduled == null) {
            return AndroidReadinessItem(
                AndroidReadinessKind.WORK_MANAGER,
                AndroidReadinessLevel.CHECKING,
                "백그라운드 작업",
                "WorkManager 등록 상태를 확인하고 있습니다."
            )
        }
        if (!scheduled) {
            return AndroidReadinessItem(
                AndroidReadinessKind.WORK_MANAGER,
                AndroidReadinessLevel.ATTENTION,
                "WorkManager 미등록",
                "주기 동기화 작업이 보이지 않아 재등록이 필요합니다."
            )
        }

        val reference = inputs.lastWorkerRunAt
        if (reference == null) {
            val sinceFirstLaunch = Duration.between(inputs.firstLaunchAt, inputs.now)
            return if (sinceFirstLaunch <= workerDelayThreshold) {
                AndroidReadinessItem(
                    AndroidReadinessKind.WORK_MANAGER,
                    AndroidReadinessLevel.CHECKING,
                    "백그라운드 작업",
                    "작업은 등록됐고 첫 실행 기록을 기다리고 있습니다."
                )
            } else {
                AndroidReadinessItem(
                    AndroidReadinessKind.WORK_MANAGER,
                    AndroidReadinessLevel.ATTENTION,
                    "WorkManager 지연",
                    "등록된 작업이 2시간 넘게 실행 기록을 남기지 않았습니다."
                )
            }
        }

        val age = Duration.between(reference, inputs.now)
        return if (age > workerDelayThreshold) {
            AndroidReadinessItem(
                AndroidReadinessKind.WORK_MANAGER,
                AndroidReadinessLevel.ATTENTION,
                "WorkManager 지연",
                "마지막 백그라운드 실행이 2시간보다 오래됐습니다."
            )
        } else {
            AndroidReadinessItem(
                AndroidReadinessKind.WORK_MANAGER,
                AndroidReadinessLevel.READY,
                "백그라운드 작업",
                "WorkManager가 등록되어 최근 실행됐습니다."
            )
        }
    }

    private fun syncItem(inputs: AndroidReadinessInputs): AndroidReadinessItem {
        val failureIsLatest = inputs.lastSyncFailureAt?.let { failure ->
            inputs.lastSyncSuccessAt == null || !failure.isBefore(inputs.lastSyncSuccessAt)
        } == true
        if (failureIsLatest && inputs.lastSyncError.isNotBlank()) {
            val requestSuffix = inputs.lastSyncRequestId.trim()
                .takeIf { it.isNotBlank() }
                ?.take(8)
                ?.let { " · 요청 #$it" }
                .orEmpty()
            return AndroidReadinessItem(
                AndroidReadinessKind.SYNC,
                AndroidReadinessLevel.ATTENTION,
                "마지막 동기화 실패",
                inputs.lastSyncError.take(160) + requestSuffix
            )
        }
        return AndroidReadinessItem(
            AndroidReadinessKind.SYNC,
            AndroidReadinessLevel.READY,
            "동기화",
            inputs.lastSyncSuccessAt?.let { "마지막 성공: $it" } ?: "기록된 동기화 실패가 없습니다."
        )
    }
}
