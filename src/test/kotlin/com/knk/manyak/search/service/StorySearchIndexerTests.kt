package com.knk.manyak.search.service

import com.knk.manyak.search.config.StorySearchConfig
import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.dto.StorySearchDocument
import com.knk.manyak.search.event.StorySearchReindexRunner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch.core.BulkRequest
import org.opensearch.client.opensearch.core.BulkResponse
import java.util.UUID

class StorySearchIndexerTests {
    private val client = mock(OpenSearchClient::class.java)
    private val reader = mock(StorySearchDocumentReader::class.java)
    private val admin = mock(StorySearchIndexAdmin::class.java)
    private val properties = StorySearchProperties()

    @Test
    fun `미설정 색인은 DB나 OpenSearch를 호출하지 않는다`() {
        val indexer = StorySearchIndexer(null, properties, reader, admin)
        indexer.index(1)
        indexer.reindex()
        verifyNoInteractions(reader, admin, client)
        assertNull(StorySearchConfig().openSearchClient(properties, null))
        assertNull(StorySearchConfig().storySearchHttpClient(properties))
    }

    @Test
    fun `재색인은 삭제 포함 전체 문서를 500개 페이지로 bulk 전송한다`() {
        val first = (1..500).map { StorySearchDocument(publicId = UUID.randomUUID().toString(), visible = it % 2 == 0) }
        val last = StorySearchDocument(publicId = UUID.randomUUID().toString(), visible = false)
        `when`(reader.readPage(0)).thenReturn(first)
        `when`(reader.readPage(1)).thenReturn(listOf(last))
        `when`(client.bulk(any(BulkRequest::class.java))).thenReturn(BulkResponse.Builder().took(1).errors(false).items(emptyList()).build())
        StorySearchIndexer(client, properties, reader, admin).reindex()
        verify(admin).ensureIndex()
        val captor = ArgumentCaptor.forClass(BulkRequest::class.java)
        verify(client, times(2)).bulk(captor.capture())
        assertEquals(listOf(500, 1), captor.allValues.map { it.operations().size })
        val operation = captor.allValues.last().operations().single().index<StorySearchDocument>()
        assertEquals(last.publicId, operation.id())
        assertEquals("stories-dev", operation.index())
        assertEquals(last, operation.document())
        verify(reader, never()).readPage(2)
    }

    @Test
    fun `재색인 러너는 토글이 켜질 때만 호출하고 실패를 격리한다`() {
        val indexer = mock(StorySearchIndexer::class.java)
        StorySearchReindexRunner(properties, indexer).onReady()
        verifyNoInteractions(indexer)
        doThrow(IllegalStateException("failed")).`when`(indexer).reindex()
        assertDoesNotThrow { StorySearchReindexRunner(properties.copy(reindexOnStartup = true), indexer).onReady() }
        verify(indexer).reindex()
    }

    @Test
    fun `운영 매핑은 nori mixed와 저장 전용 author를 유지한다`() {
        val request = StorySearchIndexAdmin.createRequest("stories-test")
        val mappings = request.mappings()!!.properties()
        assertEquals("korean", mappings.getValue("title").text().analyzer())
        assertEquals("epoch_millis", mappings.getValue("createdAt").date().format())
        assertEquals(false, mappings.getValue("author").`object`().enabled())
        assertEquals(false, mappings.getValue("thumbnailUrlSm").keyword().index())
        val analysis = request.settings()!!.analysis()!!
        assertEquals("mixed", analysis.tokenizer().getValue("korean_tokenizer").definition().noriTokenizer().decompoundMode()!!.jsonValue())
        assertEquals(listOf("korean_pos", "lowercase"), analysis.analyzer().getValue("korean").custom().filter())
    }
}
