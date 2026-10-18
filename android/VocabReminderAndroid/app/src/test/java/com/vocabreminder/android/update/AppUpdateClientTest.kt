package com.vocabreminder.android.update

import com.vocabreminder.android.sync.CloudSyncConfig
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.security.MessageDigest
import kotlin.random.Random

class AppUpdateClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: ServerSocket
    private val apk = Random(7).nextBytes(150_000)
    private var latestStatus = 200
    private var latestBody = ""
    private var apkBytes = apk
    private val authHeaders = mutableListOf<String?>()

    @Before
    fun start() {
        latestBody = release(apk).toJson().toString()
        server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { serve(it) }
            }
        }
    }

    /** Just enough HTTP/1.1 for the two GET endpoints. */
    private fun serve(socket: java.net.Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val path = reader.readLine().orEmpty().split(" ").getOrElse(1) { "" }
        generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.forEach { header ->
            if (header.startsWith("Authorization:", ignoreCase = true)) authHeaders += header.substringAfter(":").trim()
        }
        val (status, body) = when (path) {
            "/app/android/latest" -> latestStatus to latestBody.toByteArray()
            "/app/android/apk" -> 200 to apkBytes
            else -> 404 to ByteArray(0)
        }
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
    }

    @After
    fun stop() = server.close()

    private fun client() = AppUpdateClient(CloudSyncConfig("http://127.0.0.1:${server.localPort}/", "secret"))

    @Test
    fun readsTheAnnouncedReleaseWithTheSyncToken() {
        val latest = client().fetchLatest()!!
        assertEquals(release(apk), latest)
        assertEquals(listOf("Bearer secret"), authHeaders)
    }

    @Test
    fun noPublishedReleaseIsNotAnError() {
        latestStatus = 404
        latestBody = """{"error":{"message":"No Android update has been published."}}"""
        assertNull(client().fetchLatest())
    }

    @Test
    fun rejectsAuthFailuresAndMalformedMetadata() {
        latestStatus = 401
        assertTrue(assertThrows(AppUpdateException::class.java) { client().fetchLatest() }.message!!.contains("401"))

        latestStatus = 200
        latestBody = release(apk).toJson().put("sha256", "not-a-hash").toString()
        assertThrows(AppUpdateException::class.java) { client().fetchLatest() }
    }

    @Test
    fun downloadsAndKeepsTheFileOnlyWhenTheHashMatches() {
        val target = temp.root.resolve("cache/update.apk")
        var lastProgress = 0L
        client().download(release(apk), target) { written, _ -> lastProgress = written }
        assertArrayEquals(apk, target.readBytes())
        assertEquals(apk.size.toLong(), lastProgress)
        assertEquals(listOf("update.apk"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun aTamperedDownloadLeavesNothingBehind() {
        apkBytes = apk.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val target = temp.root.resolve("cache/update.apk")
        val error = assertThrows(AppUpdateException::class.java) { client().download(release(apk), target) }
        assertTrue(error.message!!.contains("SHA-256"))
        assertFalse(target.exists())
        assertEquals(emptyList<String>(), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun aShortOrOversizedDownloadIsRefused() {
        val target = temp.root.resolve("update.apk")
        apkBytes = apk.copyOf(apk.size - 10)
        assertThrows(AppUpdateException::class.java) { client().download(release(apk), target) }
        apkBytes = apk + byteArrayOf(1, 2, 3)
        assertThrows(AppUpdateException::class.java) { client().download(release(apk), target) }
        assertFalse(target.exists())
    }

    private fun release(bytes: ByteArray) = AppRelease(
        versionCode = 6,
        versionName = "0.4.2",
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        size = bytes.size.toLong()
    )

    private fun AppRelease.toJson() = JSONObject()
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("sha256", sha256)
        .put("size", size)
}
