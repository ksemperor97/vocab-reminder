package com.vocabreminder.android.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.vocabreminder.android.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.security.MessageDigest

/**
 * Hands a verified APK to the system installer. The user always confirms the
 * install on the system screen; this app cannot install silently. Only release
 * builds turn this on (BuildConfig.SELF_UPDATE_ENABLED); debug and deviceQa
 * builds keep it off unless the e2e run asks for it at build time.
 */
object AppUpdateInstaller {
    val enabled: Boolean get() = BuildConfig.SELF_UPDATE_ENABLED

    private val outcomes = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 4)

    /** What the installer reported after commit, for the screen that started it. */
    val installOutcomes: SharedFlow<InstallOutcome> get() = outcomes

    sealed class InstallOutcome {
        object WaitingForUser : InstallOutcome()
        object Installed : InstallOutcome()
        data class Failed(val message: String) : InstallOutcome()
    }

    fun downloadDir(context: Context): File = File(context.cacheDir, "app-update")

    fun downloadFile(context: Context, release: AppRelease): File =
        File(downloadDir(context), "vocab-reminder-${release.versionCode}.apk")

    /** Removes downloads left behind by an install that never reached the installer. */
    fun cleanupDownloads(context: Context) {
        downloadDir(context).listFiles()?.forEach { it.delete() }
    }

    fun installedVersionCode(context: Context): Long =
        packageInfo(context).let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else legacyVersionCode(it) }

    fun installedVersionName(context: Context): String = packageInfo(context).versionName.orEmpty()

    /** Whether the user allowed this app under "Install unknown apps". */
    fun canRequestInstalls(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Checks the downloaded file against the installed app; returns why it was refused, or null. */
    fun verify(context: Context, apk: File, release: AppRelease): String? {
        val installed = ApkFacts(
            packageName = context.packageName,
            versionCode = installedVersionCode(context),
            signerSha256 = installedSigners(context)
        )
        return AppUpdatePolicy.rejectionReason(installed, archiveFacts(context, apk), release)
    }

    /**
     * Copies [apk] into a PackageInstaller session and commits it. The system then
     * asks the user to confirm; [AppUpdateInstallReceiver] shows that screen. The
     * download is deleted once the session holds its own copy.
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, AppUpdateInstallReceiver::class.java)
                    .setAction(AppUpdateInstallReceiver.ACTION_INSTALL_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        } finally {
            apk.delete()
        }
    }

    internal fun report(outcome: InstallOutcome) {
        outcomes.tryEmit(outcome)
    }

    private fun packageInfo(context: Context): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, signingFlags())

    private fun installedSigners(context: Context): Set<String> = signerDigests(packageInfo(context))

    private fun archiveFacts(context: Context, apk: File): ApkFacts? {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags()) ?: return null
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else legacyVersionCode(info)
        return ApkFacts(info.packageName, versionCode, signerDigests(info))
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: info.signatures ?: emptyArray()
        } else {
            info.signatures ?: emptyArray()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: PackageInfo): Long = info.versionCode.toLong()
}

/** Receives PackageInstaller status for a committed update session. */
class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    AppUpdateInstaller.report(AppUpdateInstaller.InstallOutcome.Failed("설치 화면을 열 수 없습니다."))
                    return
                }
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                AppUpdateInstaller.report(AppUpdateInstaller.InstallOutcome.WaitingForUser)
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdateInstaller.report(AppUpdateInstaller.InstallOutcome.Installed)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                AppUpdateInstaller.report(AppUpdateInstaller.InstallOutcome.Failed("설치를 취소했습니다."))
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                AppUpdateInstaller.report(
                    AppUpdateInstaller.InstallOutcome.Failed("설치 실패 ($status) $detail".trim())
                )
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.vocabreminder.android.action.APP_UPDATE_STATUS"
    }
}
