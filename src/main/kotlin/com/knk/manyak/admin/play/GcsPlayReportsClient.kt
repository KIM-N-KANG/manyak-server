package com.knk.manyak.admin.play

import com.google.auth.oauth2.GoogleCredentials
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.*

fun interface PlayStatsAccessToken { fun token(): String }

@Component
class GooglePlayStatsAccessToken(private val properties: PlayStatsProperties) : PlayStatsAccessToken {
    private var credentials: GoogleCredentials? = null
    @Synchronized override fun token(): String {
        val scoped = credentials ?: playStatsCredentials(properties.serviceAccountKeyBase64).also { credentials = it }
        scoped.refreshIfExpired()
        return requireNotNull(scoped.accessToken).tokenValue
    }
}

/** service_account·authorized_user JSON 모두 받는다. 사용자 계정 자격에는 scope가 적용되지 않는다(no-op). */
internal fun playStatsCredentials(keyBase64: String): GoogleCredentials =
    GoogleCredentials.fromStream(java.util.Base64.getDecoder().decode(keyBase64).inputStream())
        .createScoped(listOf("https://www.googleapis.com/auth/devstorage.read_only"))

/** 토큰 발급·목록 페이지·전체 월별 다운로드가 하나의 10초 예산을 공유한다. */
@Component
class GcsPlayReportsClient(
    private val properties: PlayStatsProperties, private val mapper: ObjectMapper, private val tokens: PlayStatsAccessToken,
) : PlayReportsSource, AutoCloseable {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "play-stats-fetch").apply { isDaemon = true } }

    override fun reports(): List<ByteArray> {
        if (properties.serviceAccountKeyBase64.isBlank()) throw PlayStatsFetchFailure("no_credentials")
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        val pending = executor.submit<List<ByteArray>> { load(deadline) }
        try {
            return pending.get(remaining(deadline), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            throw PlayStatsFetchFailure("timeout")
        } catch (failure: ExecutionException) {
            throw (failure.cause as? PlayStatsFetchFailure ?: PlayStatsFetchFailure("http_error"))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PlayStatsFetchFailure("http_error")
        } finally { if (!pending.isDone) pending.cancel(true) }
    }

    private fun load(deadline: Long): List<ByteArray> {
        val token = tokens.token()
        val base = properties.storageBaseUrl.trimEnd('/') + "/storage/v1/b/${encode(properties.bucket)}/o"
        fun get(url: String): ByteArray {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofNanos(remaining(deadline)))
                .header("Authorization", "Bearer $token").GET().build()
            val pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            try {
                val response = pending.get(remaining(deadline), TimeUnit.NANOSECONDS)
                if (response.statusCode() !in 200..299) throw PlayStatsFetchFailure("http_error", response.statusCode())
                return response.body()
            } catch (_: TimeoutException) { throw PlayStatsFetchFailure("timeout") }
            finally { if (!pending.isDone) pending.cancel(true) }
        }
        val prefix = "stats/installs/installs_${properties.packageName}_"
        val overview = Regex("${Regex.escape(prefix)}[0-9]{6}_overview\\.csv")
        val names = sortedSetOf<String>(); val pages = mutableSetOf<String>()
        var page: String? = null
        do {
            val url = "$base?prefix=${encode(prefix)}" + (page?.let { "&pageToken=${encode(it)}" } ?: "")
            val json = mapper.readTree(get(url))
            json["items"]?.let { items ->
                require(items.isArray)
                items.forEach { item ->
                    val name = item["name"]; require(name != null && name.isString)
                    if (overview.matches(name.asText())) names += name.asText()
                }
            }
            page = json["nextPageToken"]?.let { require(it.isString); it.asText().takeIf(String::isNotBlank) }
            require(page == null || pages.add(page))
        } while (page != null)
        if (names.isEmpty()) throw PlayStatsFetchFailure("no_reports")
        return names.map { name -> get("$base/${encode(name)}?alt=media") }
    }
    private fun remaining(deadline: Long) = (deadline - System.nanoTime()).takeIf { it > 0 } ?: throw PlayStatsFetchFailure("timeout")
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    @PreDestroy override fun close() { executor.shutdownNow() }
}
