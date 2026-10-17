package com.vocabreminder.android.update

import com.vocabreminder.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {
    private val signer = "a".repeat(64)
    private val installed = ApkFacts("com.vocabreminder.android", 5, setOf(signer))
    private val release = AppRelease(6, "0.4.2", "b".repeat(64), 1000)

    @Test
    fun onlyAHigherVersionCodeIsAnUpdate() {
        assertTrue(AppUpdatePolicy.isNewer(release, 5))
        assertFalse(AppUpdatePolicy.isNewer(release, 6))
        assertFalse(AppUpdatePolicy.isNewer(release, 7))
    }

    @Test
    fun acceptsTheAnnouncedVersionFromTheSameSigner() {
        assertNull(AppUpdatePolicy.rejectionReason(installed, installed.copy(versionCode = 6), release))
    }

    @Test
    fun refusesAnotherSignerPackageOrVersion() {
        val good = installed.copy(versionCode = 6)
        val cases = mapOf(
            "unparseable" to null,
            "other signer" to good.copy(signerSha256 = setOf("c".repeat(64))),
            "extra signer" to good.copy(signerSha256 = setOf(signer, "c".repeat(64))),
            "unsigned" to good.copy(signerSha256 = emptySet()),
            "other package" to good.copy(packageName = "com.example.other"),
            "not what /latest announced" to good.copy(versionCode = 7),
            "downgrade" to good.copy(versionCode = 4)
        )
        for ((name, archive) in cases) {
            assertTrue("$name should be refused", AppUpdatePolicy.rejectionReason(installed, archive, release) != null)
        }
        assertTrue(
            "same version as installed",
            AppUpdatePolicy.rejectionReason(installed, installed, release.copy(versionCode = 5)) != null
        )
        assertEquals(
            "APK 서명 인증서가 지금 앱과 다릅니다.",
            AppUpdatePolicy.rejectionReason(installed, good.copy(signerSha256 = setOf("c".repeat(64))), release)
        )
    }

    @Test
    fun debugUnitTestBuildsKeepSelfUpdateOff() {
        // Release turns it on; debug only when the e2e run builds with VOCAB_E2E_SELF_UPDATE=1.
        if (System.getenv("VOCAB_E2E_SELF_UPDATE") != "1") assertFalse(BuildConfig.SELF_UPDATE_ENABLED)
    }
}
