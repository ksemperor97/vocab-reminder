package com.vocabreminder.android.capture

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CaptureProvenanceTest {
    @Test
    fun shareIntentKeepsOnlyActuallyProvidedProvenanceAndRedactsSensitiveUrlParts() {
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(
                Intent.EXTRA_TEXT,
                "Read robust at https://reader:password@example.com/article?id=42&access_token=secret#private"
            )
            .putExtra(Intent.EXTRA_TITLE, "  Research   article  ")

        val provenance = CaptureProvenanceExtractor.fromIntent(
            intent = intent,
            activityReferrer = Uri.parse("android-app://com.example.reader")
        )

        assertEquals("com.example.reader", provenance.sourceApp)
        assertEquals("Research article", provenance.pageTitle)
        assertEquals("https://example.com/article", provenance.sourceUrl)
    }

    @Test
    fun callingPackageWinsAndHttpsReferrerCanSupplyPageUrl() {
        val provenance = CaptureProvenanceExtractor.fromIntent(
            intent = Intent(Intent.ACTION_PROCESS_TEXT),
            callingPackage = "org.example.browser",
            activityReferrer = Uri.parse("https://example.org/paper?section=2&session=private")
        )

        assertEquals("org.example.browser", provenance.sourceApp)
        assertEquals("https://example.org/paper", provenance.sourceUrl)
    }

    @Test
    fun missingOrUnsupportedProvenanceIsExplicitlyUnknown() {
        val provenance = CaptureProvenanceExtractor.fromIntent(
            Intent(Intent.ACTION_SEND).setData(Uri.parse("content://private/item/1"))
        )

        assertEquals("", provenance.sourceApp)
        assertEquals("", provenance.sourceUrl)
        assertEquals("", provenance.pageTitle)
        assertEquals(CaptureProvenance.UNKNOWN_SOURCE_LABEL, provenance.sourceLabel)
    }

    @Test
    fun malformedPackageAndNonWebUrlsAreNotInventedAsSources() {
        val provenance = CaptureProvenanceExtractor.fromIntent(
            intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "perovskite"),
            callingPackage = "not a package",
            activityReferrer = Uri.parse("file:///private/document")
        )

        assertEquals(CaptureProvenance(), provenance)
    }

    @Test
    fun sanitizingDoesNotDoubleEncodeAnAlreadyEscapedPath() {
        assertEquals(
            "https://example.org/a%20paper",
            CaptureProvenanceExtractor.sanitizeUrl(
                "https://user:password@example.org/a%20paper?q=two%20words&api_key=secret#fragment"
            )
        )
    }

    @Test
    fun allQueryParametersAreDroppedSoUnknownCredentialNamesCannotLeak() {
        listOf(
            "token=SECRET&id=1",
            "id_token=SECRET",
            "authToken=SECRET",
            "access-token=SECRET",
            "client_secret=SECRET"
        ).forEach { query ->
            assertEquals(
                "https://example.org/article",
                CaptureProvenanceExtractor.sanitizeUrl("https://example.org/article?$query#private")
            )
        }
    }
}
