package com.vocabreminder.android

import androidx.compose.runtime.Composable

internal fun cloudSyncStatusText(serverUrl: String, authToken: String): String =
    if (serverUrl.isBlank() || authToken.isBlank()) {
        "자동 동기화: 설정 필요"
    } else {
        "자동 동기화: 준비됨"
    }

internal fun syncMissingConfigurationMessage(): String =
    "동기화 설정이 포함되지 않은 빌드입니다."

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DebugCloudSyncControls(
    serverUrl: String,
    authToken: String,
    onServerUrlChange: (String) -> Unit,
    onAuthTokenChange: (String) -> Unit,
    onLocalExport: () -> Unit,
    onLocalImport: () -> Unit
) = Unit
