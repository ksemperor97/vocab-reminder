package com.vocabreminder.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocabreminder.android.data.PairedServer

/** Shown on 오늘 when this device has no server: the app still works on its own. */
@Composable
internal fun NotConnectedCard(onConnect: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("서버에 연결되지 않았어요", style = MaterialTheme.typography.titleMedium)
            Text(
                "지금은 이 기기에만 저장돼요. 서버에 연결하면 뜻이 자동으로 채워지고 다른 기기와 단어장을 함께 써요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(modifier = Modifier.testTag("open_connect"), onClick = onConnect) { Text("서버에 연결") }
        }
    }
}

@Composable
internal fun ConnectSheetContent(
    server: String,
    code: String,
    running: Boolean,
    error: String,
    onServerChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("서버에 연결", style = MaterialTheme.typography.headlineSmall)
        Text(
            "이미 연결된 Mac이나 폰의 설정에서 \"다른 기기 연결\"을 누르면 6자리 코드가 나와요. 서버 Mac에서는 node server/tools/pair_code.mjs 로도 만들 수 있어요. QR 코드는 폰 카메라로 찍으면 바로 연결돼요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = server,
            onValueChange = onServerChange,
            modifier = Modifier.fillMaxWidth().testTag("connect_server"),
            singleLine = true,
            label = { Text("서버 주소") },
            placeholder = { Text("https://내-mac.tailnet.ts.net") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        OutlinedTextField(
            value = code,
            onValueChange = { onCodeChange(it.filter(Char::isDigit).take(6)) },
            modifier = Modifier.fillMaxWidth().testTag("connect_code"),
            singleLine = true,
            label = { Text("6자리 코드") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("connect_submit"),
            enabled = !running,
            onClick = onConnect
        ) { Text(if (running) "연결하는 중…" else "연결") }
    }
}

/** Settings: which server this device uses, codes for other devices, disconnect. */
@Composable
internal fun ServerConnectionPanel(
    connected: Boolean,
    paired: PairedServer?,
    serverUrl: String,
    onConnect: () -> Unit,
    onCreateCode: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("서버 연결", style = MaterialTheme.typography.titleMedium)
            if (!connected) {
                Text("연결된 서버가 없어요.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onConnect) { Text("서버에 연결") }
                return@Column
            }
            Text(
                serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                if (paired != null) "코드로 연결한 기기예요." else "앱에 들어 있는 설정으로 연결돼 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.testTag("create_pair_code"), onClick = onCreateCode) { Text("다른 기기 연결") }
                if (paired != null) TextButton(onClick = onDisconnect) { Text("연결 해제") }
            }
        }
    }
}

@Composable
internal fun PairingCodeDialog(code: String, serverUrl: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("다른 기기 연결 코드") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("새 기기의 \"서버에 연결\" 화면에 서버 주소와 이 코드를 입력하세요. 10분 동안 한 번만 쓸 수 있어요.")
                Text(
                    code.chunked(3).joinToString(" "),
                    modifier = Modifier.fillMaxWidth().testTag("pair_code_value"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 34.sp,
                    textAlign = TextAlign.Center
                )
                Text(serverUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}
