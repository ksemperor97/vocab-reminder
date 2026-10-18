package com.vocabreminder.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.scheduler.CardStatus

// Paper and ink: warm off-white pages, near-black text, one green for "I knew it"
// and one coral for "I didn't". secondary* is the remembered colour, tertiary* the
// forgotten one, so Material components pick them up without custom colours.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F2A37),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6EAEE),
    onPrimaryContainer = Color(0xFF1F2A37),
    secondary = Color(0xFF0F6E56),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1F5EE),
    onSecondaryContainer = Color(0xFF085041),
    tertiary = Color(0xFF993C1D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAECE7),
    onTertiaryContainer = Color(0xFF712B13),
    background = Color(0xFFFAF8F4),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFAF8F4),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFF0EDE6),
    onSurfaceVariant = Color(0xFF5F5E5A),
    outline = Color(0xFFB4B2A9),
    outlineVariant = Color(0xFFE6E3DB)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8E6E1),
    onPrimary = Color(0xFF1C1B19),
    primaryContainer = Color(0xFF2E2D2A),
    onPrimaryContainer = Color(0xFFE8E6E1),
    secondary = Color(0xFF5DCAA5),
    onSecondary = Color(0xFF04342C),
    secondaryContainer = Color(0xFF085041),
    onSecondaryContainer = Color(0xFFE1F5EE),
    tertiary = Color(0xFFF0997B),
    onTertiary = Color(0xFF4A1B0C),
    tertiaryContainer = Color(0xFF712B13),
    onTertiaryContainer = Color(0xFFFAECE7),
    background = Color(0xFF161614),
    onBackground = Color(0xFFE8E6E1),
    surface = Color(0xFF161614),
    onSurface = Color(0xFFE8E6E1),
    surfaceVariant = Color(0xFF242320),
    onSurfaceVariant = Color(0xFFB4B2A9),
    outline = Color(0xFF5F5E5A),
    outlineVariant = Color(0xFF2E2D2A)
)

/** English headwords are set in a serif, like a dictionary entry. */
internal val WordFont = FontFamily.Serif

@Composable
internal fun VocabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

/** How a word is doing, in the user's terms rather than the scheduler's. */
internal enum class WordState(val label: String) {
    NEW("새 단어"),
    LEARNING("배우는 중"),
    SHAKY("헷갈림"),
    KNOWN("익힘"),
    PAUSED("보류")
}

internal fun wordState(card: VocabularyCard, weak: Boolean): WordState = when {
    card.status == CardStatus.SUSPENDED -> WordState.PAUSED
    weak || card.status == CardStatus.RELEARNING -> WordState.SHAKY
    card.status == CardStatus.MASTERED || (card.status == CardStatus.REVIEW && card.intervalDays >= 21) -> WordState.KNOWN
    card.status == CardStatus.NEW -> WordState.NEW
    else -> WordState.LEARNING
}

@Composable
internal fun StateChip(state: WordState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        WordState.SHAKY -> colors.tertiaryContainer to colors.onTertiaryContainer
        WordState.KNOWN -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(modifier = modifier, color = container, shape = MaterialTheme.shapes.extraLarge) {
        Text(
            text = state.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content
        )
    }
}
