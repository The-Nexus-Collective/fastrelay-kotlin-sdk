package io.fastrelay.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FastrelayVideoUploadTest {

    private fun testClient(engine: MockEngine): FastrelayClient = FastrelayClient(
        baseUrl = "http://localhost:8080",
        requestTimeoutMillis = 5_000,
        logger = {},
        engine = engine,
    ).apply { updateToken("test-token") }

    @Test
    fun uploadsInChunksAndReportsProgress() = runTest {
        // given a mint endpoint and a tus endpoint that acks each chunk
        val uploaded = mutableListOf<ByteArray>()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/videos/upload-url" -> respond(
                    """{"videoId": "vid_1", "uploadUrl": "http://tus.example/upload/vid_1", "protocol": "tus"}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
                request.method == HttpMethod.Patch -> {
                    val chunk = request.body.toByteArray()
                    uploaded.add(chunk)
                    val offset = request.headers["Upload-Offset"]!!.toLong() + chunk.size
                    respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", offset.toString()))
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val sdk = testClient(engine)
        val bytes = ByteArray(10) { it.toByte() }
        val progress = mutableListOf<Long>()

        // when uploading with a 4-byte chunk size
        val result = sdk.uploadVideoBytes(
            bytes = bytes,
            filename = "clip.mp4",
            mimeType = "video/mp4",
            chunkSize = 4,
            onProgress = { progress.add(it.bytesUploaded) },
        )

        // then chunks cover the payload in order and progress advances to completion
        assertEquals("vid_1", result.videoId)
        assertEquals(10L, result.bytesUploaded)
        assertEquals(listOf(4, 4, 2), uploaded.map { it.size })
        assertEquals(bytes.toList(), uploaded.flatMap { it.toList() })
        assertEquals(listOf(0L, 4L, 8L, 10L), progress)

        val patch = engine.requestHistory.first { it.method == HttpMethod.Patch }
        assertEquals("1.0.0", patch.headers["Tus-Resumable"])
        assertEquals("application/offset+octet-stream", patch.body.contentType.toString())
    }

    @Test
    fun failedPatchThrowsTypedError() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/videos/upload-url" -> respond(
                    """{"videoId": "vid_1", "uploadUrl": "http://tus.example/upload/vid_1", "protocol": "tus"}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
                else -> respondError(HttpStatusCode.InternalServerError, "boom")
            }
        }
        val sdk = testClient(engine)

        val error = assertFailsWith<FastrelayApiError> {
            sdk.uploadVideoBytes(bytes = ByteArray(4), filename = "clip.mp4", mimeType = "video/mp4")
        }
        assertEquals(500, error.status)
        assertEquals(FastrelayApiError.CODE_VIDEO_UPLOAD_FAILED, error.code)
        assertTrue(error.message.contains("boom"))
    }

    @Test
    fun rejectsNonVideoMimeTypeBeforeMinting() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        val sdk = testClient(engine)

        assertFailsWith<FastrelayApiError> {
            sdk.uploadVideoBytes(bytes = ByteArray(4), filename = "clip.gif", mimeType = "image/gif")
        }
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun nonAdvancingServerOffsetAborts() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/videos/upload-url" -> respond(
                    """{"videoId": "vid_1", "uploadUrl": "http://tus.example/upload/vid_1", "protocol": "tus"}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
                else -> respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "0"))
            }
        }
        val sdk = testClient(engine)

        val error = assertFailsWith<FastrelayApiError> {
            sdk.uploadVideoBytes(bytes = ByteArray(4), filename = "clip.mp4", mimeType = "video/mp4")
        }
        assertTrue(error.message.contains("non-advancing"))
    }
}
