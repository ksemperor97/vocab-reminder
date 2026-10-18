package com.vocabreminder.android.capture

import android.content.Intent
import android.net.Uri
import java.net.URI

data class CaptureProvenance(
    val sourceApp: String = "",
    val sourceUrl: String = "",
    val pageTitle: String = ""
) {
    val sourceLabel: String
        get() = listOf(sourceApp, pageTitle, sourceUrl)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { UNKNOWN_SOURCE_LABEL }

    companion object {
        const val UNKNOWN_SOURCE_LABEL = "출처 확인 불가"
    }
}

object CaptureProvenanceExtractor {
    private val webUrlPattern = Regex("(?i)https?://[^\\s<>\"']+")
    private val packagePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun fromIntent(
        intent: Intent?,
        callingPackage: String? = null,
        activityReferrer: Uri? = null
    ): CaptureProvenance {
        if (intent == null) return CaptureProvenance()
        val referrer = activityReferrer ?: intent.getStringExtra(Intent.EXTRA_REFERRER_NAME)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val sourceApp = sanitizePackageName(callingPackage)
            .ifBlank { packageFromReferrer(referrer) }
        val explicitUrl = sequenceOf(
            intent.dataString,
            referrer?.toString()?.takeIf { referrer.scheme in setOf("http", "https") },
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(::firstWebUrl)
        ).filterNotNull().map(::sanitizeUrl).firstOrNull { it.isNotBlank() }.orEmpty()
        val title = intent.getCharSequenceExtra(Intent.EXTRA_TITLE)
            ?.toString()
            .orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_TITLE_LENGTH)
        return CaptureProvenance(
            sourceApp = sourceApp,
            sourceUrl = explicitUrl,
            pageTitle = title
        )
    }

    fun sanitizeUrl(value: String?): String {
        val raw = value.orEmpty().trim().take(MAX_URL_LENGTH)
        if (raw.isBlank()) return ""
        return runCatching {
            val parsed = URI(raw)
            val scheme = parsed.scheme?.lowercase()
            val host = parsed.host?.lowercase()
            if (scheme !in setOf("http", "https") || host.isNullOrBlank()) return ""
            val safeHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
            val safePort = parsed.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
            val sanitized = buildString {
                append(scheme)
                append("://")
                append(safeHost)
                append(safePort)
                append(parsed.rawPath.orEmpty())
            }
            URI(sanitized).toASCIIString().take(MAX_URL_LENGTH)
        }.getOrDefault("")
    }

    private fun packageFromReferrer(referrer: Uri?): String {
        if (referrer?.scheme != "android-app") return ""
        return sanitizePackageName(referrer.host)
    }

    private fun sanitizePackageName(value: String?): String {
        val candidate = value.orEmpty().trim().take(MAX_PACKAGE_LENGTH)
        return candidate.takeIf(packagePattern::matches).orEmpty()
    }

    private fun firstWebUrl(value: String): String? {
        return webUrlPattern.find(value)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
    }

    private const val MAX_PACKAGE_LENGTH = 200
    private const val MAX_TITLE_LENGTH = 300
    private const val MAX_URL_LENGTH = 2_048
}
