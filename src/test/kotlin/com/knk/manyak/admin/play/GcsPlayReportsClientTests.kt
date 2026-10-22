package com.knk.manyak.admin.play

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.TimeUnit

class GcsPlayReportsClientTests {
    @Test fun `모든 목록 페이지에서 해당 패키지 overview만 내려받고 인증 및 월별 합산을 적용한다`() {
        MockWebServer().use { server ->
            server.start()
            val prefix = "stats/installs/installs_app.manyak_"
            server.enqueue(MockResponse().setBody("""{"items":[{"name":"${prefix}202609_overview.csv"},{"name":"${prefix}202609_country.csv"}],"nextPageToken":"next"}"""))
            server.enqueue(MockResponse().setBody("""{"items":[{"name":"${prefix}202610_overview.csv"}]}"""))
            server.enqueue(MockResponse().setBody(Buffer().write(playCsv(playRow("2026-09-30", 29)))).setHeader("Content-Encoding", "gzip"))
            server.enqueue(MockResponse().setBody(Buffer().write(playCsv(playRow("2026-10-02", 2)))).setHeader("Content-Encoding", "gzip"))
            GcsPlayReportsClient(PlayStatsProperties(serviceAccountKeyBase64 = "fake-key", storageBaseUrl = server.url("/").toString()), ObjectMapper(), PlayStatsAccessToken { "fake-token" }).use { client ->
                assertThat(PlayInstallCsv().parse(client.reports(), "app.manyak").totalInstalls).isEqualTo(31)
            }
            assertThat(server.requestCount).isEqualTo(4)
            val requests = (1..4).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
            assertThat(requests[0].requestUrl!!.queryParameter("prefix")).isEqualTo(prefix)
            assertThat(requests[1].requestUrl!!.queryParameter("pageToken")).isEqualTo("next")
            for (request in requests) assertThat(request.getHeader("Authorization")).isEqualTo("Bearer fake-token")
            for (request in requests.drop(2)) assertThat(request.requestUrl!!.queryParameter("alt")).isEqualTo("media")
        }
    }
    @Test fun `키가 없으면 토큰 발급도 하지 않고 외부 오류와 파일 없음은 고정 실패다`() {
        GcsPlayReportsClient(PlayStatsProperties(), ObjectMapper(), PlayStatsAccessToken { error("must not call") }).use {
            assertThatThrownBy { it.reports() }.isInstanceOf(PlayStatsFetchFailure::class.java).hasMessage("Play reports request failed")
        }
        MockWebServer().use { server ->
            server.start()
            for (response in listOf(MockResponse().setResponseCode(403).setBody("sensitive key"), MockResponse().setBody("{}"), MockResponse().setBody("not-json"))) {
                server.enqueue(response)
                GcsPlayReportsClient(PlayStatsProperties(serviceAccountKeyBase64 = "fake", storageBaseUrl = server.url("/").toString()), ObjectMapper(), PlayStatsAccessToken { "token" }).use {
                    assertThatThrownBy { it.reports() }.isInstanceOf(PlayStatsFetchFailure::class.java).hasMessage("Play reports request failed")
                }
            }
        }
    }
    @Test fun `본문이 멈춰도 다운로드 전체 예산은 10초다`() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}").setBodyDelay(11, TimeUnit.SECONDS))
            GcsPlayReportsClient(PlayStatsProperties(serviceAccountKeyBase64 = "fake", storageBaseUrl = server.url("/").toString()), ObjectMapper(), PlayStatsAccessToken { "token" }).use {
                val started = System.nanoTime()
                assertThatThrownBy { it.reports() }.isInstanceOf(PlayStatsFetchFailure::class.java)
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(9500), Duration.ofSeconds(12))
            }
        }
    }
}
