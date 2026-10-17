package com.vocabreminder.android.update

import com.vocabreminder.android.sync.CloudSyncConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What GET /app/android/latest says the sync server is offering. */
data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val size: Long
) {
    companion object {
        fun fromJson(json: JSONObject): AppRelease {
            val release = AppRelease(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                sha256 = json.getString("sha256").lowercase(),
                size = json.getLong("size")
            )
            if (release.versionCode <= 0 || release.size <= 0 || !SHA256_PATTERN.matches(release.sha256)) {
                throw AppUpdateException("서버의 업데이트 정보가 올바르지 않습니다.")
            }
            return release
        }

        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
    }
}

class AppUpdateException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Talks to the sync server's update endpoints with the same base URL and bearer
 * token as sync. The APK is written to [download]'s target only after its size
 * and SHA-256 match what /latest announced; a mismatch leaves no file behind.
 */
class AppUpdateClient(private val config: CloudSyncConfig) {
    /** The published release, or null when the server has none. */
    fun fetchLatest(): AppRelease? {
        val connection = open("app/android/latest", readTimeoutMillis = 10_000)
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status !in 200..299) throw AppUpdateException("업데이트 확인 실패 (HTTP $status)")
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return runCatching { AppRelease.fromJson(JSONObject(body)) }.getOrElse { error ->
                throw error as? AppUpdateException ?: AppUpdateException("서버의 업데이트 정보가 올바르지 않습니다.", error)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun download(release: AppRelease, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        val connection = open("app/android/apk", readTimeoutMillis = 30_000)
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw AppUpdateException("다운로드 실패 (HTTP $status)")
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > release.size) throw AppUpdateException("다운로드 크기가 예상보다 큽니다.")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(written, release.size)
                    }
                    output.fd.sync()
                }
            }
            if (written != release.size) throw AppUpdateException("다운로드가 중간에 끊겼습니다. 다시 시도하세요.")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != release.sha256) throw AppUpdateException("내려받은 파일의 SHA-256이 서버 정보와 다릅니다.")
            if (!partial.renameTo(target)) throw AppUpdateException("내려받은 파일을 저장하지 못했습니다.")
        } catch (error: IOException) {
            partial.delete()
            throw error as? AppUpdateException ?: AppUpdateException("다운로드 실패: ${error.message ?: error::class.java.simpleName}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, readTimeoutMillis: Int): HttpURLConnection =
        (URL(config.endpoint(path)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = readTimeoutMillis
            useCaches = false
            setRequestProperty("Authorization", "Bearer ${config.bearerToken}")
            setRequestProperty("User-Agent", "VocabReminderAndroid/0.1")
        }
}
