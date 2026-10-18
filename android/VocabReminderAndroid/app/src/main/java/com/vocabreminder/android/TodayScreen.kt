package com.vocabreminder.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun TodayScreen(
    summary: TodaySummary,
    timezone: ZoneId,
    message: String,
    needsNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onStartStudy: () -> Unit
) {
    val now = Instant.now()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(dateFormat.format(now.atZone(timezone)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("오늘", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
        if (needsNotificationPermission) NotificationPermissionCard(onRequestNotificationPermission)
        if (message.isNotBlank()) StatusSnackbar(message = message)

        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoalRing(done = summary.reviewedToday, goal = summary.dailyGoal)
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (summary.due > 0) {
                            Text(dueLine(summary), style = MaterialTheme.typography.titleMedium)
                            Text("약 ${maxOf(1, (summary.due * 20 + 59) / 60)}분", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("오늘 복습을 다 했어요", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (summary.practiceCount > 0) "더 하고 싶으면 연습해 보세요. 일정은 바뀌지 않아요." else "단어를 저장하면 여기서 복습할 수 있어요.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (summary.due > 0) {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("start_study"),
                        onClick = onStartStudy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("학습 시작", style = MaterialTheme.typography.titleMedium)
                    }
                } else if (summary.practiceCount > 0) {
                    OutlinedButton(modifier = Modifier.fillMaxWidth().height(48.dp).testTag("start_practice"), onClick = onStartStudy) {
                        Text("헷갈리는 단어 연습")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(label = "연속", value = "${summary.streakDays}일", modifier = Modifier.weight(1f))
            StatTile(label = "헷갈리는 단어", value = "${summary.shakyCount}", modifier = Modifier.weight(1f))
        }

        summary.nextDue?.let { card ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    "다음 복습 ${nextDueLabel(card.dueAt, timezone, now)} · ${card.displayWord}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun dueLine(summary: TodaySummary): String = listOfNotNull(
    summary.dueReview.takeIf { it > 0 }?.let { "복습 ${it}개" },
    summary.dueNew.takeIf { it > 0 }?.let { "새 단어 ${it}개" }
).joinToString(" · ")

private fun nextDueLabel(dueAt: Instant, timezone: ZoneId, now: Instant): String {
    val due = dueAt.atZone(timezone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.atZone(timezone).toLocalDate(), due.toLocalDate())
    return when (days) {
        0L -> "오늘 ${timeFormat.format(due)}"
        1L -> "내일 ${timeFormat.format(due)}"
        else -> "${days}일 뒤"
    }
}

@Composable
private fun GoalRing(done: Int, goal: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
        CircularProgressIndicator(
            progress = 1f,
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
            strokeWidth = 6.dp
        )
        CircularProgressIndicator(
            progress = (done.toFloat() / goal).coerceIn(0f, 1f),
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.secondary,
            strokeWidth = 6.dp
        )
        Text("$done/$goal", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
