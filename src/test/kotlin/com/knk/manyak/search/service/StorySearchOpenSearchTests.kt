package com.knk.manyak.search.service

import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.dto.StorySearchAuthor
import com.knk.manyak.search.dto.StorySearchDocument
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
import org.mockito.Mockito.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import com.knk.manyak.search.config.StorySearchConfig
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.aws.AwsSdk2Transport
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import java.net.URI
import org.opensearch.testcontainers.OpenSearchContainer
import org.testcontainers.DockerClientFactory
import java.util.UUID

/** Spring 컨텍스트 없이 실제 매핑·BM25·search_after를 검증한다. Docker가 없는 환경만 건너뛴다. */
@EnabledIf("dockerAvailable")
class StorySearchOpenSearchTests {
    @Test
    fun `실제 OpenSearch는 숨긴 문서를 제외하고 제목을 우선하며 다음 페이지를 이어준다`() {
        OpenSearchContainer<Nothing>("opensearchproject/opensearch:2.19.4").use { container ->
            container.start()
            containerHttpClient(URI.create(container.httpHostAddress)).use { http ->
                AwsSdk2Transport(http, "localhost", Region.AP_NORTHEAST_2,
                    AwsSdk2TransportOptions.builder().setMapper(StorySearchConfig.searchJsonMapper())
                        .setCredentials(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                        .build()).use { transport ->
                    val client = OpenSearchClient(transport)
                    val plugins = container.execInContainer("bin/opensearch-plugin", "list").stdout
                    val hasNori = plugins.lineSequence().any { it.trim() == "analysis-nori" }
                    println("OpenSearch 2.19.4 analysis-nori=$hasNori; test analyzer=${if (hasNori) "korean" else "standard"}")
                    val index = "stories-test"
                    client.indices().create(StorySearchIndexAdmin.createRequest(index, standardAnalyzer = !hasNori))
                    val documents = listOf(
                        doc("왕국", "여행", 1),
                        doc("여행", "왕국", 2),
                        doc("왕국", "여행", 3, false),
                    )
                    val properties = StorySearchProperties(storyIndex = index)
                    val reader = mock(StorySearchDocumentReader::class.java)
                    `when`(reader.readPage(0)).thenReturn(documents)
                    `when`(reader.read(1)).thenReturn(documents.first())
                    val indexer = StorySearchIndexer(client, properties, reader, StorySearchIndexAdmin(client, properties))
                    // 단건 전송과 운영 AWS transport의 NDJSON bulk를 모두 실제 컨테이너에 보낸다.
                    indexer.reindex()
                    client.indices().refresh { it.index(index) }
                    assertEquals(documents.size.toLong(), client.count { it.index(index) }.count())
                    // 검색 엔진 검증은 실제 컨테이너로, DB 게이트는 위 문서에 대응하는 정본 픽스처로 분리한다.
                    val repository = mock(StoryRepository::class.java)
                    `when`(repository.findAllByPublicIdIn(anyList())).thenReturn(documents.map {
                        Story(publicId = UUID.fromString(it.publicId), userId = 1, title = it.title,
                            visibility = if (it.visible) StoryVisibility.PUBLIC else StoryVisibility.PRIVATE)
                    })
                    val service = StorySearchService(client, StorySearchProperties(storyIndex = index), repository, mock(StorySearchIndexer::class.java))
                    val first = service.search("왕국", 1, null)
                    assertEquals(documents[0].toSummary(), first.items.single())
                    assertNotNull(first.nextCursor)
                    val second = service.search("왕국", 1, first.nextCursor)
                    assertEquals(documents[1].publicId, second.items.single().id)
                    assertNull(second.nextCursor)
                    assertEquals(2, service.search("왕국", 50, null).items.size)
                    assertTrue(service.search("없는검색어", 20, null).items.isEmpty())
                    // 관련도 검증 후 단건 덮어쓰기를 확인한다. 삭제된 이전 버전이 BM25 통계에 섞이지 않게 한다.
                    indexer.index(1)
                    assertEquals(documents.first(), client.get(
                        { it.index(index).id(documents.first().publicId) }, StorySearchDocument::class.java,
                    ).source())
                }
            }
        }
    }

    // 보안 플러그인을 끈 컨테이너는 HTTP다. 서명·직렬화는 AWS transport 그대로 두고,
    // 최종 소켓 주소만 일회용 컨테이너로 바꾼다. 실 AWS 자격증명·도메인은 사용하지 않는다.
    private fun containerHttpClient(endpoint: URI): SdkHttpClient {
        val delegate = ApacheHttpClient.builder().build()
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest =
                delegate.prepareRequest(HttpExecuteRequest.builder()
                    .request(request.httpRequest().toBuilder().protocol("http")
                        .host(endpoint.host).port(endpoint.port).build())
                    .contentStreamProvider(request.contentStreamProvider().orElse(null)).build())
            override fun close() = delegate.close()
        }
    }

    private fun doc(title: String, intro: String, created: Long, visible: Boolean = true) = StorySearchDocument(
        publicId = UUID.randomUUID().toString(), title = title, oneLineIntro = intro, createdAt = created,
        author = StorySearchAuthor(nickname = "작가"), visible = visible,
    )

    companion object {
        @JvmStatic
        fun dockerAvailable(): Boolean = DockerClientFactory.instance().isDockerAvailable
    }
}
