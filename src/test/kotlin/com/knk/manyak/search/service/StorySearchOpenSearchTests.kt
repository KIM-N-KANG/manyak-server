package com.knk.manyak.search.service

import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.dto.StorySearchAuthor
import com.knk.manyak.search.dto.StorySearchDocument
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
import org.mockito.Mockito.*
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.opensearch.client.json.jsonb.JsonbJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch.core.BulkRequest
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder
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
            ApacheHttpClient5TransportBuilder.builder(HttpHost.create(container.httpHostAddress))
                .setMapper(JsonbJsonpMapper()).build().use { transport ->
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
                    val bulk = BulkRequest.Builder()
                    documents.forEach { document -> bulk.operations { op -> op.index { it.index(index).id(document.publicId).document(document) } } }
                    assertFalse(client.bulk(bulk.build()).errors())
                    client.indices().refresh { it.index(index) }
                    // 검색 엔진 검증은 실제 컨테이너로, DB 게이트는 위 문서에 대응하는 정본 픽스처로 분리한다.
                    val repository = mock(StoryRepository::class.java)
                    `when`(repository.findAllByPublicIdIn(anyList())).thenReturn(documents.map {
                        Story(publicId = UUID.fromString(it.publicId), userId = 1, title = it.title,
                            visibility = if (it.visible) StoryVisibility.PUBLIC else StoryVisibility.PRIVATE)
                    })
                    val service = StorySearchService(client, StorySearchProperties(storyIndex = index), repository, mock(StorySearchIndexer::class.java))
                    val first = service.search("왕국", 1, null)
                    assertEquals(documents[0].publicId, first.items.single().id)
                    assertNotNull(first.nextCursor)
                    val second = service.search("왕국", 1, first.nextCursor)
                    assertEquals(documents[1].publicId, second.items.single().id)
                    assertNull(second.nextCursor)
                    assertEquals(2, service.search("왕국", 50, null).items.size)
                    assertTrue(service.search("없는검색어", 20, null).items.isEmpty())
                }
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
