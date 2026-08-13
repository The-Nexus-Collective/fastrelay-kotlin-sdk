package io.fastrelay.sdk

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import kotlin.coroutines.cancellation.CancellationException

data class FastrelayVideoUploadProgress(
    val bytesUploaded: Long,
    val totalBytes: Long,
) {
    val fraction: Double
        get() = if (totalBytes <= 0) 0.0 else (bytesUploaded.toDouble() / totalBytes).coerceIn(0.0, 1.0)
}

data class FastrelayVideoUploadResult(
    val videoId: String,
    val uploadUrl: String,
    val bytesUploaded: Long,
)

private val TUS_CONTENT_TYPE = ContentType("application", "offset+octet-stream")
private const val DEFAULT_CHUNK_SIZE = 50 * 1024 * 1024

/**
 * Drives a tus 1.0.0 upload of [bytes] to [uploadUrl].
 *
 * The upload URL is the one returned by [FastrelayClient.createVideoUploadUrl],
 * which is Cloudflare Stream's tus endpoint pre-authorized by the backend — no
 * fastrelay auth header is sent. Emits [onProgress] after each PATCH chunk and
 * returns the final byte offset once the full payload has been accepted.
 */
@Throws(FastrelayApiError::class, CancellationException::class)
suspend fun FastrelayClient.tusUploadBytes(
    uploadUrl: String,
    bytes: ByteArray,
    chunkSize: Int = DEFAULT_CHUNK_SIZE,
    onProgress: ((FastrelayVideoUploadProgress) -> Unit)? = null,
): Long {
    if (uploadUrl.isBlank()) {
        throw FastrelayApiError.local(
            code = FastrelayApiError.CODE_VIDEO_UPLOAD_FAILED,
            message = "uploadUrl must not be empty.",
        )
    }
    if (chunkSize <= 0) {
        throw FastrelayApiError.local(
            code = FastrelayApiError.CODE_VIDEO_UPLOAD_FAILED,
            message = "chunkSize must be positive.",
        )
    }

    val total = bytes.size.toLong()
    var offset = 0L
    onProgress?.invoke(FastrelayVideoUploadProgress(bytesUploaded = 0, totalBytes = total))

    while (offset < total) {
        val end = minOf(offset + chunkSize, total)
        val chunk = bytes.copyOfRange(offset.toInt(), end.toInt())

        val response = http.request(uploadUrl) {
            method = HttpMethod.Patch
            header("Tus-Resumable", "1.0.0")
            header("Upload-Offset", offset.toString())
            setBody(ByteArrayContent(chunk, TUS_CONTENT_TYPE))
        }

        if (response.status.value != 204 && response.status.value != 200) {
            throw FastrelayApiError(
                status = response.status.value,
                code = FastrelayApiError.CODE_VIDEO_UPLOAD_FAILED,
                message = "tus PATCH failed: ${response.status} ${response.bodyAsText()}".trim(),
            )
        }

        val reportedOffset = response.headers["Upload-Offset"]?.toLongOrNull() ?: (offset + chunk.size)
        if (reportedOffset <= offset) {
            throw FastrelayApiError.local(
                code = FastrelayApiError.CODE_VIDEO_UPLOAD_FAILED,
                message = "tus server reported non-advancing Upload-Offset $reportedOffset at offset $offset.",
            )
        }
        offset = reportedOffset

        onProgress?.invoke(FastrelayVideoUploadProgress(bytesUploaded = offset, totalBytes = total))
    }

    return offset
}

/**
 * High-level helper: mints a tus upload URL via the backend, then uploads
 * [bytes] directly to Cloudflare Stream via tus PATCH. The returned result
 * carries the `videoId`; the final `ready` state arrives via the realtime
 * `video.ready` event, or by polling [FastrelayClient.getVideo].
 */
@Throws(FastrelayApiError::class, CancellationException::class)
suspend fun FastrelayClient.uploadVideoBytes(
    bytes: ByteArray,
    filename: String,
    mimeType: String,
    chunkSize: Int = DEFAULT_CHUNK_SIZE,
    onProgress: ((FastrelayVideoUploadProgress) -> Unit)? = null,
): FastrelayVideoUploadResult {
    if (bytes.isEmpty()) {
        throw FastrelayApiError.local(
            code = FastrelayApiError.CODE_VIDEO_UPLOAD_FAILED,
            message = "uploadVideoBytes requires non-empty bytes.",
        )
    }
    if (filename.isBlank()) {
        throw FastrelayApiError.local(
            code = FastrelayApiError.CODE_VIDEO_UPLOAD_FAILED,
            message = "uploadVideoBytes requires a non-empty filename.",
        )
    }
    if (!mimeType.trim().lowercase().startsWith("video/")) {
        throw FastrelayApiError.local(
            code = FastrelayApiError.CODE_VIDEO_UPLOAD_FAILED,
            message = "uploadVideoBytes requires a video/* mimeType (got \"$mimeType\").",
        )
    }

    val mint = createVideoUploadUrl(
        filename = filename,
        sizeBytes = bytes.size.toLong(),
        mimeType = mimeType,
    )

    val uploaded = tusUploadBytes(
        uploadUrl = mint.uploadUrl,
        bytes = bytes,
        chunkSize = chunkSize,
        onProgress = onProgress,
    )

    return FastrelayVideoUploadResult(
        videoId = mint.videoId,
        uploadUrl = mint.uploadUrl,
        bytesUploaded = uploaded,
    )
}
