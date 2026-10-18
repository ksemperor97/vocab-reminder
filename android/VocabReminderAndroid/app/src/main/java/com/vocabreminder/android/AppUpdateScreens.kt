package com.vocabreminder.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vocabreminder.android.update.AppRelease
import com.vocabreminder.android.update.AppUpdateState
import java.util.Locale

@Composable
internal fun AppUpdatePanel(
    state: AppUpdateState,
    installedVersion: String,
    onCheck: () -> Unit,
    onUpdate: (AppRelease) -> Unit,
    onOpenInstallPermission: () -> Unit
) {
    if (state is AppUpdateState.Disabled) return
    Card(modifier = Modifier.testTag("app_update_panel")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("앱 업데이트", style = MaterialTheme.typography.titleMedium)
            Text("현재 버전 $installedVersion", style = MaterialTheme.typography.bodySmall)
            Text(
                modifier = Modifier.testTag("app_update_status"),
                text = appUpdateStatusText(state),
                style = MaterialTheme.typography.bodyMedium
            )
            when (state) {
                is AppUpdateState.Available -> Button(
                    modifier = Modifier.testTag("app_update_button"),
                    onClick = { onUpdate(state.release) }
                ) { Text("업데이트") }
                is AppUpdateState.NeedsPermission -> Button(
                    modifier = Modifier.testTag("app_update_permission_button"),
                    onClick = onOpenInstallPermission
                ) { Text("설치 권한 열기") }
                is AppUpdateState.Failed -> Button(
                    onClick = { state.release?.let(onUpdate) ?: onCheck() }
                ) { Text("다시 시도") }
                is AppUpdateState.Idle, is AppUpdateState.UpToDate, is AppUpdateState.Installing -> TextButton(
                    modifier = Modifier.testTag("app_update_check_button"),
                    onClick = onCheck
                ) { Text("업데이트 확인") }
                else -> Unit
            }
        }
    }
}

/** A strip above every tab while a newer version is waiting. */
@Composable
internal fun AppUpdateBanner(state: AppUpdateState, onUpdate: (AppRelease) -> Unit) {
    val release = (state as? AppUpdateState.Available)?.release ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("새 버전 ${release.versionName}이 있습니다", style = MaterialTheme.typography.bodyMedium)
            TextButton(
                modifier = Modifier.testTag("app_update_banner_button"),
                onClick = { onUpdate(release) }
            ) { Text("업데이트") }
        }
    }
}

/** Explains the one-time "Install unknown apps" switch before opening it. */
@Composable
internal fun InstallPermissionDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("설치 권한이 한 번 필요합니다") },
        text = {
            Text(
                "Play 스토어 없이 동기화 서버에서 새 버전을 받으려면 이 앱이 설치 화면을 열 수 있어야 합니다. " +
                    "다음 화면에서 ‘이 출처 허용’을 켠 뒤 뒤로 돌아오면 업데이트를 이어서 진행합니다. " +
                    "설치는 매번 직접 확인해야 하며, 단어와 설정은 그대로 유지됩니다."
            )
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("install_permission_open"), onClick = onOpenSettings) {
                Text("설정 열기")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("나중에") } }
    )
}

internal fun appUpdateStatusText(state: AppUpdateState): String = when (state) {
    AppUpdateState.Disabled, AppUpdateState.Idle -> "동기화 서버에서 새 버전을 확인합니다."
    AppUpdateState.Checking -> "업데이트 확인 중..."
    AppUpdateState.UpToDate -> "최신 버전입니다."
    is AppUpdateState.Available ->
        "새 버전 ${state.release.versionName} (${state.release.versionCode}) · ${megabytes(state.release.size)}"
    is AppUpdateState.NeedsPermission -> "설치 권한이 필요합니다. ‘이 출처 허용’을 켜고 돌아오세요."
    is AppUpdateState.Downloading -> "내려받는 중 ${state.percent}%"
    is AppUpdateState.Installing ->
        "설치 화면에서 ‘업데이트’를 누르세요. 단어와 설정은 그대로 유지됩니다."
    is AppUpdateState.Failed -> state.message
}

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1fMB", bytes / 1_048_576.0)
