package com.vocabreminder.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Suppress("UNUSED_PARAMETER")
internal fun cloudSyncStatusText(serverUrl: String, authToken: String): String =
    "서버: ${serverUrl.ifBlank { "미설정" }}"

internal fun syncMissingConfigurationMessage(): String =
    "서버 주소와 토큰을 입력하세요."

@Composable
internal fun DebugCloudSyncControls(
    serverUrl: String,
    authToken: String,
    onServerUrlChange: (String) -> Unit,
    onAuthTokenChange: (String) -> Unit,
    onLocalExport: () -> Unit,
    onLocalImport: () -> Unit
) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sync_server_url_field"),
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text("서버 주소") },
        singleLine = true
    )
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sync_auth_token_field"),
        value = authToken,
        onValueChange = onAuthTokenChange,
        label = { Text("토큰") },
        singleLine = true
    )
    Text("Debug sync fields", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            modifier = Modifier.testTag("local_sync_export_button"),
            onClick = onLocalExport
        ) {
            Text("Sync 내보내기")
        }
        Button(
            modifier = Modifier.testTag("local_sync_import_button"),
            onClick = onLocalImport
        ) {
            Text("Sync 가져오기")
        }
    }
}
