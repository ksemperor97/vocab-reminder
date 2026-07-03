package com.vocabreminder.android.capture

enum class CaptureMethod(val wireName: String) {
    MAC_SERVICE("mac_service"),
    MAC_CLIPBOARD_HOTKEY("mac_clipboard_hotkey"),
    ANDROID_PROCESS_TEXT("android_process_text"),
    ANDROID_SHARE("android_share"),
    MANUAL("manual"),
    RECOMMENDATION("recommendation")
}

data class CaptureAnalysis(
    val rawText: String,
    val normalizedText: String,
    val displayWord: String,
    val lemma: String,
    val wordCount: Int,
    val sourceSentence: String,
    val candidates: List<String>
) {
    val needsWordSelection: Boolean = wordCount >= 4
}

object CaptureNormalizer {
    private val edgePunctuation = Regex("""^[\s"'“”‘’\[\]{}()<>.,;:!?]+|[\s"'“”‘’\[\]{}()<>.,;:!?]+$""")
    private val tokenRegex = Regex("""[A-Za-z][A-Za-z'-]*""")
    private val urlLikeSpanRegex = Regex(
        """(?i)\b(?:https?://|www\.)\S+|\b[A-Za-z0-9.-]+\.(?:com|net|org|edu|gov|io|dev|app|co|kr|jp|cn|uk|de|fr|php|html?)\S*"""
    )
    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "than", "to", "of", "in", "on",
        "for", "with", "from", "by", "at", "as", "is", "are", "was", "were", "be", "been",
        "this", "that", "these", "those", "it", "its", "their", "there", "we", "you", "they"
    )

    fun analyze(rawText: String): CaptureAnalysis {
        val normalized = normalizeText(rawText)
        val tokenSource = urlLikeSpanRegex.replace(normalized, " ")
        val tokens = tokenRegex.findAll(tokenSource).map { normalizeText(it.value) }.filter { it.isNotBlank() }.toList()
        val displayWord = when {
            tokens.isEmpty() -> normalized
            tokens.size <= 3 -> tokens.joinToString(" ")
            else -> tokens.first()
        }
        val candidates = tokens
            .map { normalizeText(it).lowercase() }
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
            .take(8)

        return CaptureAnalysis(
            rawText = rawText,
            normalizedText = normalized,
            displayWord = displayWord,
            lemma = lemma(displayWord),
            wordCount = tokens.size,
            sourceSentence = if (tokens.size >= 4) normalizeSentence(rawText) else "",
            candidates = candidates
        )
    }

    fun analyzeSelection(rawText: String, selectedWord: String): CaptureAnalysis {
        val selected = normalizeText(selectedWord)
        return analyze(selected).copy(
            rawText = rawText,
            sourceSentence = normalizeSentence(rawText)
        )
    }

    fun normalizeText(value: String): String {
        return value
            .trim()
            .replace(Regex("""\s+"""), " ")
            .replace(edgePunctuation, "")
    }

    private fun normalizeSentence(value: String): String {
        return value.trim().replace(Regex("""\s+"""), " ")
    }

    fun lemma(displayWord: String): String {
        val normalized = normalizeText(displayWord).lowercase()
        if (" " in normalized) return normalized

        // guide 10.7: Android prefers dictionary headword; these heuristics cover local/offline fallback.
        return when {
            normalized.endsWith("ies") && normalized.length > 4 -> normalized.dropLast(3) + "y"
            normalized.endsWith("ied") && normalized.length > 4 -> normalized.dropLast(3) + "y"
            normalized.endsWith("ing") && normalized.length > 5 -> normalized.dropLast(3).trimTrailingDoubleConsonant()
            normalized.endsWith("ed") && normalized.length > 4 -> normalized.dropLast(2).trimTrailingDoubleConsonant()
            normalized.endsWith("ly") && normalized.length > 5 -> normalized.dropLast(2)
            normalized.endsWith("s") &&
                !normalized.endsWith("ss") &&
                !normalized.endsWith("us") &&
                !normalized.endsWith("ous") &&
                normalized.length > 3 -> normalized.dropLast(1)
            else -> normalized
        }
    }

    private fun String.trimTrailingDoubleConsonant(): String {
        if (length < 2) return this
        val last = last()
        val previous = this[length - 2]
        return if (last == previous && last !in setOf('a', 'e', 'i', 'o', 'u')) dropLast(1) else this
    }
}
