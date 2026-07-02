package com.vocabreminder.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.StoredReviewEvent
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.notifications.ReminderNotifications
import com.vocabreminder.android.notifications.ReminderScheduler
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.scheduler.SchedulerSettings
import java.time.Duration
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderNotifications.ensureChannel(this)
        requestNotificationPermissionIfNeeded()

        val processText = if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        } else {
            ""
        }

        setContent {
            VocabReminderApp(initialWord = processText)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}

@Composable
private fun VocabReminderApp(initialWord: String) {
    val context = LocalContext.current
    val store = remember { LocalVocabularyStore(context) }
    val cards = remember { mutableStateListOf<VocabularyCard>() }
    val events = remember { mutableStateListOf<StoredReviewEvent>() }
    var word by remember { mutableStateOf(initialWord.trim()) }
    var meaning by remember { mutableStateOf("") }
    var example by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun reload() {
        cards.clear()
        cards.addAll(store.cards().reversed())
        events.clear()
        events.addAll(store.events().reversed())
    }

    LaunchedEffect(Unit) {
        reload()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "Vocabulary Reminder",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "단어를 저장하면 10분 뒤 알림으로 다시 묻습니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                item {
                    AddWordForm(
                        word = word,
                        meaning = meaning,
                        example = example,
                        message = message,
                        onWordChange = { word = it },
                        onMeaningChange = { meaning = it },
                        onExampleChange = { example = it },
                        onSave = {
                            val trimmedWord = word.trim()
                            val trimmedMeaning = meaning.trim()
                            if (trimmedWord.isBlank() || trimmedMeaning.isBlank()) {
                                message = "단어와 뜻을 입력하세요."
                                return@AddWordForm
                            }
                            val now = Instant.now()
                            val dueAt = Scheduler.initialDueAt(now, SchedulerSettings())
                            val card = store.addCard(
                                displayWord = trimmedWord,
                                meaningKo = trimmedMeaning,
                                exampleSentence = example,
                                dueAt = dueAt,
                                now = now
                            )
                            ReminderScheduler.schedule(context, card, now)
                            word = ""
                            meaning = ""
                            example = ""
                            message = "${card.displayWord} 저장됨 · 첫 알림까지 ${Duration.between(now, card.dueAt).toMinutes()}분"
                            reload()
                        }
                    )
                }

                item {
                    Text(
                        text = "저장된 단어",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (cards.isEmpty()) {
                    item {
                        Text(
                            text = "아직 저장된 단어가 없습니다.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    items(cards, key = { it.id }) { card ->
                        CardSummary(card)
                    }
                }

                item {
                    Text(
                        text = "복습 이벤트 ${events.size}개",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                items(events.take(8), key = { it.id }) { event ->
                    Text(
                        text = "${event.response.name.lowercase()} · ${event.previousStatus.name.lowercase()} → ${event.nextStatus.name.lowercase()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AddWordForm(
    word: String,
    meaning: String,
    example: String,
    message: String,
    onWordChange: (String) -> Unit,
    onMeaningChange: (String) -> Unit,
    onExampleChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("word_field"),
                value = word,
                onValueChange = onWordChange,
                label = { Text("단어") },
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("meaning_field"),
                value = meaning,
                onValueChange = onMeaningChange,
                label = { Text("뜻") },
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("example_field"),
                value = example,
                onValueChange = onExampleChange,
                label = { Text("예문") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("save_button"),
                    onClick = onSave
                ) {
                    Text("저장")
                }
            }
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CardSummary(card: VocabularyCard) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(text = card.displayWord, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = card.meaningKo, style = MaterialTheme.typography.bodyMedium)
            if (card.exampleSentence.isNotBlank()) {
                Text(text = card.exampleSentence, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "status=${card.status.name.lowercase()} · interval=${card.intervalDays}d · due=${card.dueAt}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
