package com.vocabreminder.android.update

/** Identity of an APK: the installed app or a downloaded archive. */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    /** SHA-256 of each certificate that signed the APK contents, lowercase hex. */
    val signerSha256: Set<String>
)

/**
 * Pure checks that decide whether a downloaded APK may be handed to the
 * installer. Android would also refuse a different signer, but only after the
 * user confirmed; checking first gives a clear message instead.
 */
object AppUpdatePolicy {
    fun isNewer(release: AppRelease, installedVersionCode: Long): Boolean =
        release.versionCode > installedVersionCode

    /** Returns why [archive] must not be installed, or null when it is the announced update. */
    fun rejectionReason(installed: ApkFacts, archive: ApkFacts?, release: AppRelease): String? = when {
        archive == null -> "내려받은 파일이 올바른 APK가 아닙니다."
        archive.packageName != installed.packageName -> "다른 앱의 APK입니다 (${archive.packageName})."
        archive.versionCode != release.versionCode ->
            "APK 버전(${archive.versionCode})이 서버 정보(${release.versionCode})와 다릅니다."
        archive.versionCode <= installed.versionCode -> "이미 같은 버전이거나 더 새 버전이 설치되어 있습니다."
        archive.signerSha256.isEmpty() || installed.signerSha256.isEmpty() -> "APK 서명을 확인할 수 없습니다."
        archive.signerSha256 != installed.signerSha256 -> "APK 서명 인증서가 지금 앱과 다릅니다."
        else -> null
    }
}
