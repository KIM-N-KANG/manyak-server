package com.knk.manyak.search.service

import com.knk.manyak.search.config.StorySearchConfig
import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.dto.StorySearchDocument
import com.knk.manyak.search.scheduler.StorySearchReindexScheduler
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.OfficialStoryAccount
import com.knk.manyak.story.service.StoryAuthorResolver
import com.knk.manyak.story.service.StoryService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.hc.core5.http.HttpHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIf
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.opensearch.client.opensearch.OpenSearchClient
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID

/** 검색 서버처럼 보안 플러그인을 켠 OpenSearch에 계정으로 붙는 것과, 저장소 장애 때 대체 검색까지 걸리는 시간을 확인한다(KNK-2101). */
@EnabledIf("dockerAvailable")
class StorySearchSecuredClusterTests {
    private fun properties(username: String = "admin", password: String = PASSWORD) =
        StorySearchProperties(storyIndex = "stories-home-test", username = username, password = password)

    private fun host() = HttpHost("http", container.host, container.getMappedPort(9200))

    @Test
    fun `보안 플러그인을 켠 OpenSearch에 계정으로 색인하고 검색하며 틀린 비밀번호는 거절된다`() {
        StorySearchConfig.homeTransport(host(), properties()).use { transport ->
            val client = OpenSearchClient(transport)
            val index = properties().storyIndex
            client.indices().create(StorySearchIndexAdmin.createRequest(index, standardAnalyzer = true))
            val id = UUID.randomUUID().toString()
            client.index { it.index(index).id(id).document(StorySearchDocument(publicId = id, title = "검색 서버 왕국", visible = true)) }
            client.indices().refresh { it.index(index) }
            assertThat(client.count { it.index(index) }.count()).isEqualTo(1)
        }
        // 401은 전송 계층에서 IOException으로 올라온다. 검색 서비스는 이것도 질의 실패로 보고 대체 검색한다.
        for (rejected in listOf(properties(password = "wrong-password"), StorySearchProperties())) {
            StorySearchConfig.homeTransport(host(), rejected).use { transport ->
                val error = assertThrows<java.io.IOException> { OpenSearchClient(transport).count { it.index(properties().storyIndex) } }
                assertThat(error.message).contains("Unauthorized").doesNotContain("wrong-password")
            }
        }
    }

    @Test
    fun `정기 재색인은 인덱스가 없으면 만들고 색인에서 빠진 공개 스토리를 채운다`() {
        val props = properties().copy(storyIndex = "stories-home-reindex")
        StorySearchConfig.homeTransport(host(), props).use { transport ->
            val client = OpenSearchClient(transport)
            val documents = listOf("빠진 왕국", "남은 왕국").map { StorySearchDocument(publicId = UUID.randomUUID().toString(), title = it, visible = true) }
            val reader = mock(StorySearchDocumentReader::class.java)
            `when`(reader.readPage(0)).thenReturn(documents)
            // 테스트 이미지에는 analysis-nori가 없어 인덱스 생성만 표준 분석기로 한다. 생성 여부 판단과 순서는 운영 코드 그대로다.
            val admin = object : StorySearchIndexAdmin(client, props) {
                override fun ensureIndex() {
                    if (client.indices().exists { it.index(props.storyIndex) }.value()) return
                    client.indices().create(createRequest(props.storyIndex, standardAnalyzer = true))
                }
            }
            val indexer = StorySearchIndexer(client, props, reader, admin)
            val redis = mock(StringRedisTemplate::class.java)
            @Suppress("UNCHECKED_CAST") // Mockito가 제네릭 인터페이스의 목을 원시 타입으로 만든다.
            val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
            `when`(redis.opsForValue()).thenReturn(values)
            `when`(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).thenReturn(true)
            assertThat(client.indices().exists { it.index(props.storyIndex) }.value()).isFalse()

            StorySearchReindexScheduler(client, indexer, redis).run()

            client.indices().refresh { it.index(props.storyIndex) }
            assertThat(client.count { it.index(props.storyIndex) }.count()).isEqualTo(2)
            val hit = client.get({ it.index(props.storyIndex).id(documents[0].publicId) }, StorySearchDocument::class.java)
            assertThat(hit.found()).isTrue()
        }
    }

    @Test
    fun `저장소가 내려가거나 3초 넘게 응답하지 않으면 대체 검색으로 답하고 걸린 시간을 남긴다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val down = timedSearch(HttpHost("http", "127.0.0.1", closedPort))
        ServerSocket(0).use { silent ->
            // 연결은 받고 응답하지 않는다.
            val slow = timedSearch(HttpHost("http", "127.0.0.1", silent.localPort))
            println("대체 검색 시간: 저장소 내림 ${down.first}ms, 응답 없음 ${slow.first}ms")
            assertThat(down.second).isEqualTo(1.0)
            assertThat(slow.second).isEqualTo(1.0)
            assertThat(down.first).isLessThan(3_000)
            assertThat(slow.first).isBetween(2_900, 4_000)
        }
    }

    /** 기본 응답 한도(3초)로 검색해 (걸린 ms, 대체 횟수)를 돌려준다. */
    private fun timedSearch(host: HttpHost): Pair<Long, Double> = StorySearchConfig.homeTransport(host, StorySearchProperties()).use { transport ->
        val fallback = mock(StoryService::class.java)
        `when`(fallback.searchListedByText("왕국", 20)).thenReturn(emptyList())
        val meters = SimpleMeterRegistry()
        val service = StorySearchService(
            OpenSearchClient(transport), StorySearchProperties(), storyPlayReaderOver(mock(StoryRepository::class.java)),
            mock(StorySearchIndexer::class.java), mock(OfficialStoryAccount::class.java), mock(StoryAuthorResolver::class.java), fallback, meters,
        )
        val started = System.nanoTime()
        val page = service.search("왕국", 20, null)
        assertThat(page.nextCursor).isNull()
        (System.nanoTime() - started) / 1_000_000 to meters.counter("manyak.story.search.fallback").count()
    }

    companion object {
        private const val PASSWORD = "Kn2101!SearchHome#pw"

        @JvmStatic
        fun dockerAvailable(): Boolean = DockerClientFactory.instance().isDockerAvailable

        // 보안 플러그인은 켜고 HTTP TLS만 끈다. 검색 서버는 TLS를 앞단 프록시가 맡는다.
        private val lazyContainer = lazy {
            GenericContainer("opensearchproject/opensearch:2.19.4")
                .withEnv("discovery.type", "single-node")
                .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", PASSWORD)
                .withEnv("plugins.security.ssl.http.enabled", "false")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(401).withStartupTimeout(Duration.ofMinutes(3)))
                .apply { start() }
        }
        private val container by lazyContainer

        @JvmStatic @AfterAll
        fun stop() {
            if (lazyContainer.isInitialized()) container.stop()
        }
    }
}
