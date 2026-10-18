package com.vocabreminder.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocabreminder.android.data.StoredContext
import com.vocabreminder.android.data.StoredDefinitionCandidate
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.scheduler.ReviewResponse
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val SWIPE_THRESHOLD = 220f

@Composable
internal fun StudySessionScreen(
    session: StudySession,
    cards: List<VocabularyCard>,
    contexts: List<StoredContext>,
    definitionCandidates: List<StoredDefinitionCandidate>,
    weakIds: Set<String>,
    onReveal: () -> Unit,
    onAnswer: (ReviewResponse) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("close_study")) {
                    Icon(Icons.Outlined.Close, contentDescription = "학습 닫기")
                }
                LinearProgressIndicator(
                    progress = session.index.toFloat() / session.cardIds.size.coerceAtLeast(1),
                    modifier = Modifier.weight(1f).height(6.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.width(12.dp))
                Text("${minOf(session.index + 1, session.cardIds.size)}/${session.cardIds.size}", style = MaterialTheme.typography.labelLarge)
            }
            if (session.practice) {
                Text(
                    "연습 중이에요. 복습 일정은 바뀌지 않아요.",
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val card = session.currentId?.let { id -> cards.firstOrNull { it.id == id } }
            if (session.finished || card == null) {
                StudyFinished(session = session, onClose = onClose)
            } else {
                StudyCard(
                    card = card,
                    sentence = StudyPlan.sentence(card, contexts),
                    otherMeanings = definitionCandidates
                        .filter { it.cardId == card.id }
                        .map { it.parsedMeaningKo.trim() }
                        .filter { it.isNotBlank() && it != card.meaningKo }
                        .distinct()
                        .take(2),
                    state = wordState(card, card.id in weakIds),
                    revealed = session.revealed,
                    practice = session.practice,
                    onReveal = onReveal,
                    onAnswer = onAnswer
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.StudyCard(
    card: VocabularyCard,
    sentence: String,
    otherMeanings: List<String>,
    state: WordState,
    revealed: Boolean,
    practice: Boolean,
    onReveal: () -> Unit,
    onAnswer: (ReviewResponse) -> Unit
) {
    val offset = remember(card.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(16.dp))
    OutlinedCard(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .graphicsLayer {
                translationX = offset.value
                rotationZ = offset.value / 60f
            }
            .pointerInput(card.id, revealed) {
                if (!revealed) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val value = offset.value
                        scope.launch {
                            if (abs(value) > SWIPE_THRESHOLD) {
                                onAnswer(if (value > 0) ReviewResponse.REMEMBERED else ReviewResponse.FORGOTTEN)
                            } else {
                                offset.animateTo(0f)
                            }
                        }
                    },
                    onHorizontalDrag = { _, delta -> scope.launch { offset.snapTo(offset.value + delta) } }
                )
            }
            .clickable(enabled = !revealed, onClick = onReveal)
            .testTag("study_card")
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StateChip(state, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.weight(1f))
            Text(card.displayWord, fontFamily = WordFont, fontSize = 40.sp, textAlign = TextAlign.Center)
            if (card.partOfSpeech.isNotBlank()) {
                Text(partOfSpeechLabel(card.partOfSpeech), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sentence.isNotBlank()) {
                Text(
                    highlighted(sentence, listOf(card.displayWord, card.lemma)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (revealed) {
                Divider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(card.meaningKo, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.testTag("study_meaning"))
                if (otherMeanings.isNotEmpty()) {
                    Text(
                        "다른 뜻: ${otherMeanings.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text("눌러서 뜻 보기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(16.dp))
    if (revealed) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f).height(56.dp).testTag("study_forgot"),
                onClick = { onAnswer(ReviewResponse.FORGOTTEN) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            ) { Text("몰랐어요", style = MaterialTheme.typography.titleMedium) }
            Button(
                modifier = Modifier.weight(1f).height(56.dp).testTag("study_remembered"),
                onClick = { onAnswer(ReviewResponse.REMEMBERED) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) { Text("알았어요", style = MaterialTheme.typography.titleMedium) }
        }
    } else {
        Button(
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("study_reveal"),
            onClick = onReveal
        ) { Text("뜻 보기", style = MaterialTheme.typography.titleMedium) }
    }
    TextButton(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        enabled = !practice,
        onClick = { onAnswer(ReviewResponse.UNAVAILABLE) }
    ) {
        Text(
            when {
                practice && revealed -> "밀어서 넘길 수 있어요"
                practice -> ""
                revealed -> "나중에 · 밀어서 넘기기"
                else -> "나중에"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.StudyFinished(session: StudySession, onClose: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("다 했어요", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
        Text(
            listOfNotNull(
                "알았어요 ${session.remembered}",
                "몰랐어요 ${session.forgotten}",
                session.later.takeIf { it > 0 }?.let { "나중에 $it" }
            ).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (session.practice) "연습이라 복습 일정은 그대로예요." else "몰랐던 단어는 곧 다시 알림으로 물어볼게요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.weight(1f))
    Button(modifier = Modifier.fillMaxWidth().height(52.dp).testTag("finish_study"), onClick = onClose) {
        Text("닫기", style = MaterialTheme.typography.titleMedium)
    }
}

private fun highlighted(sentence: String, words: List<String>) = buildAnnotatedString {
    val match = words.filter { it.isNotBlank() }
        .mapNotNull { word -> Regex("\\b${Regex.escape(word)}\\w*", RegexOption.IGNORE_CASE).find(sentence) }
        .minByOrNull { it.range.first }
    if (match == null) {
        append(sentence)
        return@buildAnnotatedString
    }
    append(sentence.substring(0, match.range.first))
    withStyle(SpanStyle(fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)) { append(match.value) }
    append(sentence.substring(match.range.last + 1))
}

private fun partOfSpeechLabel(value: String): String = when (value.lowercase()) {
    "noun" -> "명사"
    "verb" -> "동사"
    "adjective" -> "형용사"
    "adverb" -> "부사"
    "preposition" -> "전치사"
    "conjunction" -> "접속사"
    "pronoun" -> "대명사"
    else -> value
}
