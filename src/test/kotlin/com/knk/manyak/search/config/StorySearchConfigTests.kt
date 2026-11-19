package com.knk.manyak.search.config

import com.knk.manyak.support.startOnLoopback
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.apache.hc.core5.http.HttpHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.aws.AwsSdk2Transport
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.Base64

class StorySearchConfigTests {
    private val runner = ApplicationContextRunner().withUserConfiguration(StorySearchConfig::class.java)

    @Test
    fun `계정 쌍이나 접근 토큰 쌍의 한쪽만 있으면 기동하지 않고 값은 오류에 싣지 않는다`() {
        val halves = listOf("username=searcher", "password=pw-secret-1", "access-client-id=cid-1", "access-client-secret=tok-secret-2")
        for (endpoint in listOf("", "search.example.com")) {
            for (half in halves) {
                runner.withPropertyValues("manyak.search.opensearch.endpoint=$endpoint", "manyak.search.opensearch.$half").run { context ->
                    assertThat(context).hasFailed()
                    val messages = generateSequence(context.startupFailure) { it.cause }.joinToString("\n") { it.message.orEmpty() }
                    assertThat(messages).contains("함께 설정").doesNotContain("pw-secret-1", "tok-secret-2", "cid-1")
                }
            }
        }
    }

    @Test
    fun `둘 다 비면 인증 없이 연결하고 관리형 도메인 주소는 전환 기간 동안 SigV4로 연결한다`() {
        runner.withPropertyValues("manyak.search.opensearch.endpoint=search.example.com").run { context ->
            assertThat(context.getBean(OpenSearchClient::class.java)._transport()).isInstanceOf(ApacheHttpClient5Transport::class.java)
        }
        runner.withPropertyValues(
            "manyak.search.opensearch.endpoint=search.example.com",
            "manyak.search.opensearch.username=searcher", "manyak.search.opensearch.password=pw",
            "manyak.search.opensearch.access-client-id=cid", "manyak.search.opensearch.access-client-secret=tok",
        ).run { context ->
            assertThat(context.getBean(OpenSearchClient::class.java)._transport()).isInstanceOf(ApacheHttpClient5Transport::class.java)
        }
        runner.withPropertyValues("manyak.search.opensearch.endpoint=search-manyak-logs-x.ap-northeast-2.es.amazonaws.com").run { context ->
            assertThat(context.getBean(OpenSearchClient::class.java)._transport()).isInstanceOf(AwsSdk2Transport::class.java)
        }
        for (bad in listOf("https://search.example.com", "search.example.com/path")) {
            runner.withPropertyValues("manyak.search.opensearch.endpoint=$bad").run { assertThat(it).hasFailed() }
        }
    }

    @Test
    fun `설정 값의 문자열 표현은 비밀번호와 토큰을 가린다`() {
        val text = StorySearchProperties(username = "searcher", password = "pw-secret-1", accessClientId = "cid-1", accessClientSecret = "tok-secret-2").toString()
        assertThat(text).doesNotContain("pw-secret-1", "tok-secret-2", "cid-1")
    }

    @Test
    fun `계정과 접근 토큰을 모든 요청 헤더로 보내고 비어 있으면 보내지 않는다`() {
        MockWebServer().startOnLoopback().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200))
            val host = HttpHost("http", server.hostName, server.port)
            val withAuth = StorySearchProperties(username = "searcher", password = "pw", accessClientId = "cid", accessClientSecret = "tok")
            StorySearchConfig.homeTransport(host, withAuth).use { OpenSearchClient(it).indices().exists { e -> e.index("stories-test") } }
            StorySearchConfig.homeTransport(host, StorySearchProperties()).use { OpenSearchClient(it).indices().exists { e -> e.index("stories-test") } }
            val first = server.takeRequest()
            assertThat(first.getHeader("Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString("searcher:pw".toByteArray()))
            assertThat(first.getHeader("CF-Access-Client-Id")).isEqualTo("cid")
            assertThat(first.getHeader("CF-Access-Client-Secret")).isEqualTo("tok")
            val second = server.takeRequest()
            assertThat(second.headers.names()).doesNotContain("Authorization", "CF-Access-Client-Id", "CF-Access-Client-Secret")
        }
    }
}
