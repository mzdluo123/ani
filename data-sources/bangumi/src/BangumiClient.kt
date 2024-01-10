/*
 * Ani
 * Copyright (C) 2022-2024 Him188
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.him188.ani.datasources.bangumi

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.core.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.datasources.api.Paged
import me.him188.ani.datasources.bangumi.client.BangumiClientEpisodes
import me.him188.ani.datasources.bangumi.client.BangumiClientSubjects
import me.him188.ani.datasources.bangumi.client.BangumiEpType
import me.him188.ani.datasources.bangumi.client.BangumiEpisode
import me.him188.ani.datasources.bangumi.models.search.BangumiSort
import me.him188.ani.datasources.bangumi.models.subjects.BangumiSubject
import me.him188.ani.datasources.bangumi.models.subjects.BangumiSubjectDetails
import me.him188.ani.datasources.bangumi.models.subjects.BangumiSubjectImageSize
import me.him188.ani.datasources.bangumi.models.subjects.BangumiSubjectType
import me.him188.ani.utils.serialization.toJsonArray
import okhttp3.OkHttpClient
import org.openapitools.client.apis.BangumiApi
import org.openapitools.client.models.RelatedPerson

interface BangumiClient : Closeable {
    // Bangumi open API: https://github.com/bangumi/api/blob/master/open-api/api.yml

    /*
    {
    "access_token":"YOUR_ACCESS_TOKEN",
    "expires_in":604800,
    "token_type":"Bearer",
    "scope":null,
    "refresh_token":"YOUR_REFRESH_TOKEN"
    "user_id" : USER_ID
    }
     */

    @Serializable
    data class GetAccessTokenResponse(
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("user_id") val userId: Long,
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
    )

    /**
     * 用 OAuth 回调的 code 换 access token 和 refresh token
     */
    suspend fun exchangeTokens(code: String, callbackUrl: String): GetAccessTokenResponse
    suspend fun refreshAccessToken(refreshToken: String, callbackUrl: String): GetAccessTokenResponse

    @Serializable
    data class GetTokenStatusResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("expires") val expires: Long, // timestamp
        @SerialName("user_id") val userId: Int,
    )

    suspend fun getTokenStatus(accessToken: String): GetTokenStatusResponse

    val api: BangumiApi

    val subjects: BangumiClientSubjects

    val episodes: BangumiClientEpisodes

    companion object Factory {
        fun create(
            clientId: String,
            clientSecret: String,
            httpClientConfiguration: HttpClientConfig<*>.() -> Unit = {}
        ): BangumiClient =
            BangumiClientImpl(clientId, clientSecret, httpClientConfiguration)
    }
}

private const val BANGUMI_API_HOST = "https://api.bgm.tv"
private const val BANGUMI_HOST = "https://bgm.tv"

internal class BangumiClientImpl(
    private val clientId: String,
    private val clientSecret: String,
    httpClientConfiguration: HttpClientConfig<*>.() -> Unit = {},
) : BangumiClient {
    override suspend fun exchangeTokens(code: String, callbackUrl: String): BangumiClient.GetAccessTokenResponse {
        val resp = httpClient.post("$BANGUMI_HOST/oauth/access_token") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("grant_type", "authorization_code")
                put("client_id", clientId)
                put("client_secret", clientSecret)
                put("code", code)
                put("redirect_uri", callbackUrl)
            })
        }

        if (!resp.status.isSuccess()) {
            throw IllegalStateException("Failed to get access token: $resp")
        }

        return resp.body<BangumiClient.GetAccessTokenResponse>()
    }

    override suspend fun refreshAccessToken(
        refreshToken: String,
        callbackUrl: String
    ): BangumiClient.GetAccessTokenResponse {
        val resp = httpClient.post("$BANGUMI_HOST/oauth/access_token") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("grant_type", "refresh_token")
                put("client_id", clientId)
                put("client_secret", clientSecret)
                put("refresh_token", refreshToken)
                put("redirect_uri", callbackUrl)
            })
        }

        if (!resp.status.isSuccess()) {
            throw IllegalStateException("Failed to get access token: $resp")
        }

        return resp.body<BangumiClient.GetAccessTokenResponse>()
    }

    override suspend fun getTokenStatus(accessToken: String): BangumiClient.GetTokenStatusResponse {
        val resp = httpClient.post("$BANGUMI_HOST/oauth/token_status") {
            parameter("access_token", accessToken)
        }

        if (!resp.status.isSuccess()) {
            throw IllegalStateException("Failed to get token status: $resp")
        }

        return resp.body<BangumiClient.GetTokenStatusResponse>()
    }

    override val api = BangumiApi(BANGUMI_API_HOST, OkHttpClient.Builder().apply {
        this.followRedirects(true)
        addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().addHeader(
                    "User-Agent",
                    "him188/ani/3.0.0-beta01 (Android) (https://github.com/Him188/ani)"
                ).build()
            )
        }
    }.build())

    private val httpClient = HttpClient(CIO) {
        httpClientConfiguration()
        install(HttpRequestRetry) {
            maxRetries = 3
            delayMillis { 2000 }
        }
        install(HttpTimeout)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    @Serializable
    private data class SearchSubjectByKeywordsResponse(
        val total: Int,
        val data: List<BangumiSubject>? = null,
    )

    override val subjects: BangumiClientSubjects = object : BangumiClientSubjects {
        override suspend fun searchSubjectByKeywords(
            keyword: String,
            offset: Int?,
            limit: Int?,
            sort: BangumiSort?,
            types: List<BangumiSubjectType>?,
            tags: List<String>?,
            airDates: List<String>?,
            ratings: List<String>?,
            ranks: List<String>?,
            nsfw: Boolean?,
        ): Paged<BangumiSubject> {
            val resp = httpClient.post("$BANGUMI_API_HOST/v0/search/subjects") {
                parameter("offset", offset)
                parameter("limit", limit)

                contentType(ContentType.Application.Json)
                val req = buildJsonObject {
                    put("keyword", keyword)
                    sort?.let { sort ->
                        put("sort", sort.id)
                    }

                    put("filter", buildJsonObject {
                        types?.let { types -> put("type", types.map { it.id }.toJsonArray()) }
                        ranks?.let { put("rank", it.toJsonArray()) }
                        tags?.let { put("tag", it.toJsonArray()) }
                        airDates?.let { put("air_date", it.toJsonArray()) }
                        ratings?.let { put("rating", it.toJsonArray()) }
                        nsfw?.let { put("nsfw", it) }
                    })
                }
                setBody(req)
            }

            if (!resp.status.isSuccess()) {
                throw IllegalStateException("Failed to search subject by keywords: $resp")
            }

            val body = resp.body<SearchSubjectByKeywordsResponse>()
            return body.run {
                Paged(
                    total,
                    data == null || (offset ?: 0) + data.size < total,
                    data.orEmpty()
                )
            }
        }

        override suspend fun getSubjectById(id: Int): BangumiSubjectDetails? {
            val resp = httpClient.get("$BANGUMI_API_HOST/v0/subjects/${id}")

            if (!resp.status.isSuccess()) {
                throw IllegalStateException("Failed to get subject by id: $resp")
            }

            return resp.body()
        }

        override suspend fun getSubjectImageUrl(id: Int, size: BangumiSubjectImageSize): String {
            return Companion.getSubjectImageUrl(id, size)
        }

        override suspend fun getSubjectPersonsById(id: Int): List<RelatedPerson> {
            return runInterruptible(Dispatchers.IO) { api.getRelatedPersonsBySubjectId(id) }
//            val resp = httpClient.get("$BANGUMI_API_HOST/v0/subjects/${id}/persons")
//
//            if (!resp.status.isSuccess()) {
//                throw IllegalStateException("Failed to get subject persons by id: $resp")
//            }
//            return resp.body()
        }
    }


    @Serializable
    private data class GetEpisodesResp(
        val total: Int,
        val data: List<BangumiEpisode>,
    )

    override val episodes: BangumiClientEpisodes = object : BangumiClientEpisodes {
        override suspend fun getEpisodes(
            subjectId: Long,
            type: BangumiEpType,
            limit: Int?,
            offset: Int?,
        ): Paged<BangumiEpisode> {
            val resp = httpClient.get("$BANGUMI_API_HOST/v0/episodes") {
                parameter("subject_id", subjectId)
                parameter("type", type.id)
                parameter("limit", limit)
                parameter("offset", offset)
            }

            if (!resp.status.isSuccess()) {
                throw IllegalStateException("Failed to get episodes: $resp")
            }

            return resp.body<GetEpisodesResp>().run {
                Paged(total, (offset ?: 0) + data.size < total, data)
            }
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        fun getSubjectImageUrl(id: Int, size: BangumiSubjectImageSize): String {
            return "$BANGUMI_API_HOST/v0/subjects/${id}/image?type=${size.id.lowercase()}"
        }
    }
}
