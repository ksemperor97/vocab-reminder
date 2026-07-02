package com.vocabreminder.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VocabReminderApp()
        }
    }
}

@Composable
private fun VocabReminderApp() {
    MaterialTheme {
        Surface {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Vocabulary Reminder",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Android notification review shell for M0.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
