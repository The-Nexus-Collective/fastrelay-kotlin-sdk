package io.fastrelay.sdk

import io.fastrelay.sdk.internal.ErrorParser
import io.fastrelay.sdk.internal.FastrelayJson
import io.fastrelay.sdk.internal.Redaction
import io.fastrelay.sdk.internal.TokenHolder
import io.fastrelay.sdk.internal.defaultHttpEngine
import io.fastrelay.sdk.internal.request.AddMemberRequest
import io.fastrelay.sdk.internal.request.BatchFollowRequest
import io.fastrelay.sdk.internal.request.BatchGetRequest
import io.fastrelay.sdk.internal.request.CreateActivityRequest
import io.fastrelay.sdk.internal.request.CreateCommentRequest
import io.fastrelay.sdk.internal.request.CreateFeedRequest
import io.fastrelay.sdk.internal.request.CreateFlagRequest
import io.fastrelay.sdk.internal.request.CreateMuteRequest
import io.fastrelay.sdk.internal.request.CreatePollRequest
import io.fastrelay.sdk.internal.request.CreateUserRequest
import io.fastrelay.sdk.internal.request.FeedSettingsRequest
import io.fastrelay.sdk.internal.request.FeedbackRequest
import io.fastrelay.sdk.internal.request.FollowRequest
import io.fastrelay.sdk.internal.request.PollOptionInput
import io.fastrelay.sdk.internal.request.ReactionRequest
import io.fastrelay.sdk.internal.request.UpdateActivityRequest
import io.fastrelay.sdk.internal.request.UpdateCommentRequest
import io.fastrelay.sdk.internal.request.UpdateUserRequest
import io.fastrelay.sdk.internal.request.VideoUploadUrlRequest
import io.fastrelay.sdk.internal.request.VisibilityRequest
import io.fastrelay.sdk.internal.request.VoteRequest
import io.fastrelay.sdk.model.CursorPage
import io.fastrelay.sdk.model.FastrelayActivity
import io.fastrelay.sdk.model.FastrelayBookmark
import io.fastrelay.sdk.model.FastrelayCapabilities
import io.fastrelay.sdk.model.FastrelayComment
import io.fastrelay.sdk.model.FastrelayCommentReaction
import io.fastrelay.sdk.model.FastrelayFeedActivityPin
import io.fastrelay.sdk.model.FastrelayFeedMember
import io.fastrelay.sdk.model.FastrelayFeedback
import io.fastrelay.sdk.model.FastrelayFile
import io.fastrelay.sdk.model.FastrelayFollow
import io.fastrelay.sdk.model.FastrelayModerationFlag
import io.fastrelay.sdk.model.FastrelayPoll
import io.fastrelay.sdk.model.FastrelayReaction
import io.fastrelay.sdk.model.FastrelayUser
import io.fastrelay.sdk.model.FastrelayUserMute
import io.fastrelay.sdk.model.FastrelayVideo
import io.fastrelay.sdk.model.FastrelayVideoUploadUrl
import io.fastrelay.sdk.model.FeedActivitiesPage
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import io.fastrelay.sdk.internal.epochMillis
import io.fastrelay.sdk.realtime.FastrelayRealtime
import io.fastrelay.sdk.realtime.KtorRealtimeTransport
import io.fastrelay.sdk.realtime.RealtimeTokenAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class FastrelayClient(
    val baseUrl: String = DEFAULT_BASE_URL,
    internal val tokenProvider: (suspend () -> String)? = null,
    internal val requestTimeoutMillis: Long = 30_000,
    internal val logger: (String) -> Unit = { println(it) },
    engine: HttpClientEngine? = null,
) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val tokenHolder = TokenHolder(scope, tokenProvider)

    internal val http: HttpClient = HttpClient(engine ?: defaultHttpEngine()) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = this@FastrelayClient.requestTimeoutMillis
        }
        install(ContentNegotiation) {
            json(FastrelayJson)
        }
        install(WebSockets)
    }

    private var realtimeInstance: FastrelayRealtime? = null

    val realtime: FastrelayRealtime
        get() = realtimeInstance ?: FastrelayRealtime(
            scope = scope,
            transport = KtorRealtimeTransport(http),
            baseUrl = baseUrl,
            tokens = object : RealtimeTokenAccess {
                override fun currentToken(): String? = tokenHolder.current()
                override val canRefresh: Boolean get() = tokenProvider != null
                override suspend fun refresh(failedToken: String?): String = tokenHolder.refresh(failedToken)
            },
            nowMillis = { epochMillis() },
            logger = logger,
        ).also { realtimeInstance = it }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.fastrelay.io"
    }

    init {
        warnIfPlaintextNonLoopback()
    }

    val currentToken: String? get() = tokenHolder.current()

    fun updateToken(token: String) = tokenHolder.updateToken(token)

    fun close() {
        http.close()
        scope.cancel()
    }

    override fun toString(): String = "FastrelayClient(baseUrl=$baseUrl)"

    internal suspend inline fun <reified T> requestJson(
        method: HttpMethod,
        path: String,
        body: Any? = null,
        query: Map<String, String?> = emptyMap(),
        options: FastrelayRequestOptions? = null,
    ): T {
        val response = requestRaw(method, path, body, query, options)
        return FastrelayJson.decodeFromString(response.bodyAsText())
    }

    internal suspend fun requestUnit(
        method: HttpMethod,
        path: String,
        body: Any? = null,
        query: Map<String, String?> = emptyMap(),
        options: FastrelayRequestOptions? = null,
    ) {
        requestRaw(method, path, body, query, options)
    }

    internal suspend fun requestRaw(
        method: HttpMethod,
        path: String,
        body: Any? = null,
        query: Map<String, String?> = emptyMap(),
        options: FastrelayRequestOptions? = null,
        configure: (HttpRequestBuilder.() -> Unit)? = null,
    ): HttpResponse {
        val token = requireToken()
        var response = executeOnce(method, path, body, query, options, token, configure)
        if (response.status.value == 401 && tokenProvider != null) {
            val refreshed = tokenHolder.refresh(failedToken = token)
            response = executeOnce(method, path, body, query, options, refreshed, configure)
            if (response.status.value == 401) {
                throw FastrelayApiError(
                    status = 401,
                    code = FastrelayApiError.CODE_AUTH_EXPIRED,
                    message = "Request kept failing with 401 after a token refresh.",
                    hint = "The refreshed token was rejected; check the app credentials backing the tokenProvider.",
                )
            }
        }
        if (!response.status.isSuccess()) {
            throw ErrorParser.parse(response.status.value, response.bodyAsText()) { name ->
                response.headers[name]
            }
        }
        return response
    }

    private suspend fun executeOnce(
        method: HttpMethod,
        path: String,
        body: Any?,
        query: Map<String, String?>,
        options: FastrelayRequestOptions?,
        token: String,
        configure: (HttpRequestBuilder.() -> Unit)?,
    ): HttpResponse {
        val url = URLBuilder(baseUrl).apply {
            encodedPath = path
            query.forEach { (name, value) -> if (value != null) parameters.append(name, value) }
        }.build()
        try {
            return http.request(url) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $token")
                options?.idempotencyKey?.let { header("Idempotency-Key", it) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                configure?.invoke(this)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: FastrelayApiError) {
            throw error
        } catch (error: Throwable) {
            throw FastrelayApiError(
                status = 0,
                code = FastrelayApiError.CODE_NETWORK_ERROR,
                message = Redaction.redact(
                    error.message ?: (error::class.simpleName ?: "Network request failed"),
                    tokenHolder.current(),
                ),
                hint = "Check that the fastrelay backend is reachable at $baseUrl.",
            )
        }
    }

    // Dynamic path components (ids, feed names) must be encoded before interpolation so
    // reserved characters like '/', '?', '#', '%' stay one path segment instead of rerouting.
    private fun enc(value: String): String = value.encodeURLPathPart()

    internal fun requireToken(): String =
        tokenHolder.current() ?: throw FastrelayApiError.local(
            code = FastrelayApiError.CODE_NO_TOKEN,
            message = "No user token available.",
            hint = "Call connectUser or updateToken before making requests.",
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun getCapabilities(): FastrelayCapabilities =
        requestJson(HttpMethod.Get, "/v1/me/capabilities")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun createUser(
        id: String? = null,
        displayName: String? = null,
        profileData: JsonObject? = null,
        role: String = "user",
        options: FastrelayRequestOptions? = null,
    ): FastrelayUser =
        requestJson(
            HttpMethod.Post,
            "/v1/users",
            body = CreateUserRequest(id, displayName, profileData, role),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun getUser(id: String): FastrelayUser =
        requestJson(HttpMethod.Get, "/v1/users/${enc(id)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun updateUser(
        id: String,
        displayName: String? = null,
        profileData: JsonObject? = null,
        role: String? = null,
    ): FastrelayUser =
        requestJson(HttpMethod.Patch, "/v1/users/${enc(id)}", body = UpdateUserRequest(displayName, profileData, role))

    fun feed(group: String, id: String): FastrelayFeed = FastrelayFeed(this, group, id)

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun getOrCreateFeed(group: String, id: String, userId: String? = null): FastrelayFeed {
        requestUnit(HttpMethod.Post, "/v1/feeds/${enc(group)}/${enc(id)}", body = CreateFeedRequest(userId))
        return FastrelayFeed(this, group, id)
    }

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun getFeedActivities(
        group: String,
        id: String,
        limit: Int = 25,
        cursor: String? = null,
        view: String? = null,
        markSeen: Boolean? = null,
        markRead: String? = null,
        filters: Map<String, String> = emptyMap(),
    ): FeedActivitiesPage =
        requestJson(
            HttpMethod.Get,
            "/v1/feeds/${enc(group)}/${enc(id)}/activities",
            query = buildMap {
                put("limit", limit.toString())
                put("cursor", cursor)
                put("view", view)
                put("markSeen", markSeen?.toString())
                put("markRead", markRead)
                filters.forEach { (key, value) -> put("filter[$key]", value) }
            },
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun deleteFeed(group: String, id: String) =
        requestUnit(HttpMethod.Delete, "/v1/feeds/${enc(group)}/${enc(id)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun setFeedVisibility(group: String, id: String, level: String) =
        requestUnit(HttpMethod.Put, "/v1/feeds/${enc(group)}/${enc(id)}/visibility", body = VisibilityRequest(level))

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun updateFeedSettings(group: String, id: String, followApproval: String? = null) =
        requestUnit(HttpMethod.Put, "/v1/feeds/${enc(group)}/${enc(id)}/settings", body = FeedSettingsRequest(followApproval))

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun addFeedMember(
        group: String,
        id: String,
        userId: String,
        role: String = "member",
        options: FastrelayRequestOptions? = null,
    ): FastrelayFeedMember =
        requestJson(
            HttpMethod.Post,
            "/v1/feeds/${enc(group)}/${enc(id)}/members",
            body = AddMemberRequest(userId, role),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun removeFeedMember(group: String, id: String, userId: String) =
        requestUnit(HttpMethod.Delete, "/v1/feeds/${enc(group)}/${enc(id)}/members/${enc(userId)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun listFeedMembers(
        group: String,
        id: String,
        limit: Int = 25,
        cursor: String? = null,
    ): CursorPage<FastrelayFeedMember> =
        requestJson(
            HttpMethod.Get,
            "/v1/feeds/${enc(group)}/${enc(id)}/members",
            query = mapOf("limit" to limit.toString(), "cursor" to cursor),
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun pinActivity(
        group: String,
        id: String,
        activityId: String,
        options: FastrelayRequestOptions? = null,
    ): FastrelayFeedActivityPin =
        requestJson(HttpMethod.Post, "/v1/feeds/${enc(group)}/${enc(id)}/activities/${enc(activityId)}/pin", options = options)

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun unpinActivity(group: String, id: String, activityId: String) =
        requestUnit(HttpMethod.Delete, "/v1/feeds/${enc(group)}/${enc(id)}/activities/${enc(activityId)}/pin")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun addActivity(
        type: String,
        feeds: List<String>,
        text: String? = null,
        userId: String? = null,
        custom: JsonObject? = null,
        visibility: String = "public",
        expiresAt: String? = null,
        options: FastrelayRequestOptions? = null,
    ): FastrelayActivity =
        requestJson(
            HttpMethod.Post,
            "/v1/activities",
            body = CreateActivityRequest(
                type = type,
                text = text,
                userId = userId,
                feeds = feeds,
                custom = custom,
                visibility = visibility,
                expiresAt = expiresAt,
            ),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun getActivity(id: String): FastrelayActivity =
        requestJson(HttpMethod.Get, "/v1/activities/${enc(id)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun updateActivity(id: String, text: String? = null, custom: JsonObject? = null): FastrelayActivity =
        requestJson(HttpMethod.Patch, "/v1/activities/${enc(id)}", body = UpdateActivityRequest(text, custom))

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun deleteActivity(id: String) =
        requestUnit(HttpMethod.Delete, "/v1/activities/${enc(id)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun batchGetActivities(ids: List<String>): List<FastrelayActivity> {
        if (ids.isEmpty()) return emptyList()
        val page: CursorPage<FastrelayActivity> =
            requestJson(HttpMethod.Post, "/v1/activities/batch", body = BatchGetRequest(ids))
        return page.data
    }

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun addReaction(
        activityId: String,
        type: String,
        userId: String? = null,
        options: FastrelayRequestOptions? = null,
    ): FastrelayReaction =
        requestJson(
            HttpMethod.Post,
            "/v1/activities/${enc(activityId)}/reactions",
            body = ReactionRequest(type, userId),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun removeReaction(activityId: String, reactionId: String) =
        requestUnit(HttpMethod.Delete, "/v1/activities/${enc(activityId)}/reactions/${enc(reactionId)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun listReactions(
        activityId: String,
        type: String? = null,
        limit: Int = 25,
        cursor: String? = null,
    ): CursorPage<FastrelayReaction> =
        requestJson(
            HttpMethod.Get,
            "/v1/activities/${enc(activityId)}/reactions",
            query = mapOf("type" to type, "limit" to limit.toString(), "cursor" to cursor),
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun addComment(
        activityId: String,
        text: String,
        parentId: String? = null,
        mentionedUsers: List<String>? = null,
        custom: JsonObject? = null,
        userId: String? = null,
        options: FastrelayRequestOptions? = null,
    ): FastrelayComment =
        requestJson(
            HttpMethod.Post,
            "/v1/activities/${enc(activityId)}/comments",
            body = CreateCommentRequest(text, parentId, mentionedUsers, custom, userId),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun updateComment(commentId: String, text: String, custom: JsonObject? = null): FastrelayComment =
        requestJson(HttpMethod.Patch, "/v1/comments/${enc(commentId)}", body = UpdateCommentRequest(text, custom))

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun deleteComment(commentId: String) =
        requestUnit(HttpMethod.Delete, "/v1/comments/${enc(commentId)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun listComments(
        activityId: String,
        sort: String? = null,
        limit: Int = 25,
        cursor: String? = null,
    ): CursorPage<FastrelayComment> =
        requestJson(
            HttpMethod.Get,
            "/v1/activities/${enc(activityId)}/comments",
            query = mapOf("sort" to sort, "limit" to limit.toString(), "cursor" to cursor),
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun listReplies(commentId: String, limit: Int = 25, cursor: String? = null): CursorPage<FastrelayComment> =
        requestJson(
            HttpMethod.Get,
            "/v1/comments/${enc(commentId)}/replies",
            query = mapOf("limit" to limit.toString(), "cursor" to cursor),
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun addCommentReaction(
        commentId: String,
        type: String,
        userId: String? = null,
        options: FastrelayRequestOptions? = null,
    ): FastrelayCommentReaction =
        requestJson(
            HttpMethod.Post,
            "/v1/comments/${enc(commentId)}/reactions",
            body = ReactionRequest(type, userId),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun removeCommentReaction(commentId: String, reactionId: String) =
        requestUnit(HttpMethod.Delete, "/v1/comments/${enc(commentId)}/reactions/${enc(reactionId)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun addBookmark(activityId: String, options: FastrelayRequestOptions? = null): FastrelayBookmark =
        requestJson(HttpMethod.Post, "/v1/activities/${enc(activityId)}/bookmarks", options = options)

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun removeBookmark(activityId: String) =
        requestUnit(HttpMethod.Delete, "/v1/activities/${enc(activityId)}/bookmarks")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun listBookmarks(limit: Int = 25, cursor: String? = null): CursorPage<FastrelayBookmark> =
        requestJson(HttpMethod.Get, "/v1/me/bookmarks", query = mapOf("limit" to limit.toString(), "cursor" to cursor))

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun createPoll(
        activityId: String,
        question: String,
        options: List<Pair<String, String>>,
        maxVotesPerUser: Int = 1,
        expiresAt: String? = null,
        anonymous: Boolean = false,
        requestOptions: FastrelayRequestOptions? = null,
    ): FastrelayPoll =
        requestJson(
            HttpMethod.Post,
            "/v1/activities/${enc(activityId)}/polls",
            body = CreatePollRequest(
                question = question,
                options = options.map { (id, text) -> PollOptionInput(id, text) },
                maxVotesPerUser = maxVotesPerUser,
                expiresAt = expiresAt,
                anonymous = anonymous,
            ),
            options = requestOptions,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun getPollForActivity(activityId: String): FastrelayPoll =
        requestJson(HttpMethod.Get, "/v1/activities/${enc(activityId)}/polls")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun getPoll(pollId: String): FastrelayPoll =
        requestJson(HttpMethod.Get, "/v1/polls/${enc(pollId)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun vote(pollId: String, optionId: String, options: FastrelayRequestOptions? = null): FastrelayPoll =
        requestJson(HttpMethod.Post, "/v1/polls/${enc(pollId)}/votes", body = VoteRequest(optionId), options = options)

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun removeVote(pollId: String) =
        requestUnit(HttpMethod.Delete, "/v1/polls/${enc(pollId)}/votes")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun followFeed(
        group: String,
        id: String,
        targetFeedId: String,
        activityCopyLimit: Int = 100,
        options: FastrelayRequestOptions? = null,
    ): FastrelayFollow =
        requestJson(
            HttpMethod.Post,
            "/v1/feeds/${enc(group)}/${enc(id)}/follows",
            body = FollowRequest(targetFeedId, activityCopyLimit),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun unfollowFeed(group: String, id: String, targetFeedId: String, keepHistory: Boolean = false) =
        requestUnit(
            HttpMethod.Delete,
            "/v1/feeds/${enc(group)}/${enc(id)}/follows/${enc(targetFeedId)}",
            query = mapOf("keepHistory" to keepHistory.toString()),
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun listFollowers(group: String, id: String, limit: Int = 25, cursor: String? = null): CursorPage<FastrelayFollow> =
        requestJson(
            HttpMethod.Get,
            "/v1/feeds/${enc(group)}/${enc(id)}/followers",
            query = mapOf("limit" to limit.toString(), "cursor" to cursor),
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun listFollowing(group: String, id: String, limit: Int = 25, cursor: String? = null): CursorPage<FastrelayFollow> =
        requestJson(
            HttpMethod.Get,
            "/v1/feeds/${enc(group)}/${enc(id)}/following",
            query = mapOf("limit" to limit.toString(), "cursor" to cursor),
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun batchFollowFeed(
        group: String,
        id: String,
        targets: List<String>,
        activityCopyLimit: Int = 50,
        options: FastrelayRequestOptions? = null,
    ): CursorPage<FastrelayFollow> =
        requestJson(
            HttpMethod.Post,
            "/v1/feeds/${enc(group)}/${enc(id)}/follows/batch",
            body = BatchFollowRequest(targets, activityCopyLimit),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun listFollowRequests(
        group: String,
        id: String,
        limit: Int = 25,
        cursor: String? = null,
    ): CursorPage<FastrelayFollow> =
        requestJson(
            HttpMethod.Get,
            "/v1/feeds/${enc(group)}/${enc(id)}/follow-requests",
            query = mapOf("limit" to limit.toString(), "cursor" to cursor),
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun approveFollowRequest(group: String, id: String, requestId: String): FastrelayFollow =
        requestJson(HttpMethod.Post, "/v1/feeds/${enc(group)}/${enc(id)}/follow-requests/${enc(requestId)}/approve")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun rejectFollowRequest(group: String, id: String, requestId: String) =
        requestUnit(HttpMethod.Post, "/v1/feeds/${enc(group)}/${enc(id)}/follow-requests/${enc(requestId)}/reject")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun uploadFile(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        type: String? = null,
        options: FastrelayRequestOptions? = null,
    ): FastrelayFile {
        if (filename.substringAfterLast('.', "").isBlank()) {
            throw FastrelayApiError.local(
                code = FastrelayApiError.CODE_INVALID_FILE_NAME,
                message = "Filename '$filename' has no extension.",
                hint = "The backend requires an extension to derive the stored file name.",
            )
        }
        val response = requestRaw(HttpMethod.Post, "/v1/files", options = options) {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                            },
                        )
                        if (type != null) append("type", type)
                    },
                ),
            )
        }
        return FastrelayJson.decodeFromString(response.bodyAsText())
    }

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun getFile(fileId: String): FastrelayFile =
        requestJson(HttpMethod.Get, "/v1/files/${enc(fileId)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun deleteFile(fileId: String) =
        requestUnit(HttpMethod.Delete, "/v1/files/${enc(fileId)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun createVideoUploadUrl(
        filename: String,
        sizeBytes: Long,
        mimeType: String,
        options: FastrelayRequestOptions? = null,
    ): FastrelayVideoUploadUrl =
        requestJson(
            HttpMethod.Post,
            "/v1/videos/upload-url",
            body = VideoUploadUrlRequest(filename, sizeBytes, mimeType),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun getVideo(videoId: String): FastrelayVideo =
        requestJson(HttpMethod.Get, "/v1/videos/${enc(videoId)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun deleteVideo(videoId: String) =
        requestUnit(HttpMethod.Delete, "/v1/videos/${enc(videoId)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun submitFeedback(
        activityId: String,
        type: String,
        options: FastrelayRequestOptions? = null,
    ): FastrelayFeedback {
        require(type == "show_more" || type == "show_less") {
            "Feedback type must be 'show_more' or 'show_less'."
        }
        return requestJson(
            HttpMethod.Post,
            "/v1/activities/${enc(activityId)}/feedback",
            body = FeedbackRequest(type),
            options = options,
        )
    }

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun createFlag(
        targetType: String,
        targetId: String,
        reason: String,
        description: String? = null,
        options: FastrelayRequestOptions? = null,
    ): FastrelayModerationFlag =
        requestJson(
            HttpMethod.Post,
            "/v1/moderation/flags",
            body = CreateFlagRequest(targetType, targetId, reason, description),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun deleteFlag(flagId: String) =
        requestUnit(HttpMethod.Delete, "/v1/moderation/flags/${enc(flagId)}")

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun createMute(
        userId: String,
        type: String = "personal",
        expiresAt: String? = null,
        options: FastrelayRequestOptions? = null,
    ): FastrelayUserMute =
        requestJson(
            HttpMethod.Post,
            "/v1/moderation/mutes",
            body = CreateMuteRequest(userId, type, expiresAt),
            options = options,
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun removeMute(userId: String, type: String = "personal") =
        requestUnit(HttpMethod.Delete, "/v1/moderation/mutes/${enc(userId)}", query = mapOf("type" to type))

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun listMutes(
        type: String = "personal",
        limit: Int = 25,
        cursor: String? = null,
    ): CursorPage<FastrelayUserMute> =
        requestJson(
            HttpMethod.Get,
            "/v1/moderation/mutes",
            query = mapOf("type" to type, "limit" to limit.toString(), "cursor" to cursor),
        )

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun createActivityWithPoll(
        type: String,
        feeds: List<String>,
        question: String,
        pollOptions: List<Pair<String, String>>,
        text: String? = null,
        userId: String? = null,
        custom: JsonObject? = null,
        pollExpiresAt: String? = null,
        options: FastrelayRequestOptions? = null,
    ): CreateActivityWithPollResult {
        val activity = addActivity(
            type = type,
            feeds = feeds,
            text = text,
            userId = userId,
            custom = custom,
            options = options,
        )
        return attachPoll(activity, question, pollOptions, pollExpiresAt)
    }

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun attachPoll(
        activity: FastrelayActivity,
        question: String,
        pollOptions: List<Pair<String, String>>,
        pollExpiresAt: String? = null,
    ): CreateActivityWithPollResult {
        val poll = try {
            createPoll(activity.id, question, pollOptions, expiresAt = pollExpiresAt)
        } catch (error: FastrelayApiError) {
            return CreateActivityWithPollResult(activity = activity, pollError = error)
        }
        return try {
            val stamped = updateActivity(activity.id, custom = activity.custom.withPollId(poll.id))
            CreateActivityWithPollResult(activity = stamped, poll = poll)
        } catch (error: FastrelayApiError) {
            CreateActivityWithPollResult(activity = activity, poll = poll, stampError = error)
        }
    }

    private fun JsonObject?.withPollId(pollId: String): JsonObject = buildJsonObject {
        this@withPollId?.forEach { (key, value) -> put(key, value) }
        put("pollId", JsonPrimitive(pollId))
    }

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun connectUser(
        userId: String,
        displayName: String? = null,
        upsertUser: Boolean = true,
    ): String {
        val provider = tokenProvider ?: throw FastrelayApiError.local(
            code = FastrelayApiError.CODE_NO_TOKEN,
            message = "connectUser requires a tokenProvider to obtain the user token.",
        )
        val token = provider()
        // Realtime is bound to the previous user's token; drop it so events don't leak across users.
        realtimeInstance?.disconnect()
        tokenHolder.updateToken(token)
        if (upsertUser) {
            createUser(id = userId, displayName = displayName)
        }
        return token
    }

    fun disconnectUser() {
        realtimeInstance?.disconnect()
        tokenHolder.clear()
    }

    private fun warnIfPlaintextNonLoopback() {
        val url = runCatching { URLBuilder(baseUrl).build() }.getOrNull() ?: return
        val loopback = url.host in setOf("localhost", "127.0.0.1", "::1")
        if (url.protocol.name == "http" && !loopback) {
            logger(
                "fastrelay WARNING: plaintext http to non-loopback host ${url.host}. " +
                    "Requests, tokens, and the realtime ws:// URL (which carries the token as a query param) " +
                    "are all readable on the network path. Use https for anything beyond a local demo.",
            )
        }
    }
}
