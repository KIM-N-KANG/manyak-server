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
import org.opensearch.client.opensearch.core.IndexRequest
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem
import org.opensearch.client.opensearch.core.bulk.OperationType
import org.opensearch.client.opensearch._types.ErrorCause
import org.opensearch.client.util.ObjectBuilder
import org.slf4j.LoggerFactory
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
        indexer.indexAll(listOf(1, 2))
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

    private val ok = BulkResponse.Builder().took(1).errors(false).items(emptyList()).build()
    private fun document(n: Long) = StorySearchDocument(publicId = UUID(0, n).toString(), title = "스토리$n")

    /** 지정한 클래스 로거(기본 StorySearchIndexer)의 WARN 메시지를 모은다. */
    private fun <T> warnings(source: Class<*> = StorySearchIndexer::class.java, block: () -> T): List<String> {
        val logger = LoggerFactory.getLogger(source) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try { block() } finally { logger.detachAppender(appender) }
        return appender.list.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun `묶음 색인은 500건씩 나눠 묶음마다 한 번 읽고 bulk 요청 한 번으로 보내며 단건 색인은 부르지 않는다`() {
        val ids = (1L..501L).toList()
        `when`(reader.readAll(ids.take(500))).thenReturn(ids.take(500).map(::document))
        `when`(reader.readAll(listOf(501L))).thenReturn(listOf(document(501)))
        `when`(client.bulk(any(BulkRequest::class.java))).thenReturn(ok)
        val indexer = StorySearchIndexer(client, properties, reader, admin)

        indexer.indexAll(emptyList())
        verifyNoInteractions(reader, admin, client)
        indexer.indexAll(ids)

        verify(admin, times(1)).ensureIndex()
        verify(reader).readAll(ids.take(500))
        verify(reader).readAll(listOf(501L))
        val captor = ArgumentCaptor.forClass(BulkRequest::class.java)
        verify(client, times(2)).bulk(captor.capture())
        assertEquals(listOf(500, 1), captor.allValues.map { it.operations().size })
        assertEquals(ids.map { document(it).publicId }, captor.allValues.flatMap { request -> request.operations().map { it.index<Any>().id() } })
        assertEquals(0, mockingDetails(client).invocations.count { it.method.name == "index" })
        verify(reader, never()).read(anyLong())
    }

    @Test
    fun `한 묶음의 읽기 실패와 요청 실패는 다음 묶음을 막지 않고 묶음 건수와 예외 이름만 남긴다`() {
        val ids = (1L..1001L).toList()
        val (first, second, third) = ids.chunked(500)
        `when`(reader.readAll(first)).thenThrow(IllegalStateException("private db detail 1"))
        `when`(reader.readAll(second)).thenReturn(second.map(::document))
        `when`(reader.readAll(third)).thenReturn(third.map(::document))
        `when`(client.bulk(any(BulkRequest::class.java))).thenThrow(RuntimeException("private response body")).thenReturn(ok)

        val logs = warnings { StorySearchIndexer(client, properties, reader, admin).indexAll(ids) }

        val captor = ArgumentCaptor.forClass(BulkRequest::class.java)
        verify(client, times(2)).bulk(captor.capture())
        assertEquals(listOf(500, 1), captor.allValues.map { it.operations().size })
        assertEquals(listOf(
            "스토리 묶음 색인 실패 (stories=500, error=IllegalStateException)",
            "스토리 묶음 색인 실패 (stories=500, error=RuntimeException)",
        ), logs)
    }

    @Test
    fun `bulk 항목 실패는 묶음 건수와 실패 건수를 남긴다`() {
        val ids = listOf(1L, 2L, 3L)
        `when`(reader.readAll(ids)).thenReturn(ids.map(::document))
        fun item(n: Long, status: Int, error: Boolean) = BulkResponseItem.of { item ->
            item.operationType(OperationType.Index).index("stories-dev").id(document(n).publicId).status(status)
                .also { if (error) it.error(ErrorCause.of { cause -> cause.type("mapper_parsing_exception").reason("private reason") }) }
        }
        `when`(client.bulk(any(BulkRequest::class.java))).thenReturn(BulkResponse.of {
            it.took(1).errors(true).items(listOf(item(1, 201, false), item(2, 400, true), item(3, 201, false)))
        })

        val logs = warnings { StorySearchIndexer(client, properties, reader, admin).indexAll(ids) }

        assertEquals(listOf("스토리 묶음 색인 일부 실패 (stories=3, failed=1)"), logs)
    }

    @Test
    fun `단건 색인과 묶음 색인은 같은 인덱스와 문서 ID와 직렬화 문서를 보낸다`() {
        val doc = StorySearchDocument(publicId = UUID(0, 7).toString(), title = "서린의 약속", genres = listOf("판타지"),
            characterNames = listOf("하람", "가온"), author = com.knk.manyak.search.dto.StorySearchAuthor(nickname = "작가"),
            turnCount = 12, likeCount = 3, createdAt = 1793502245678, visible = true)
        `when`(reader.read(7)).thenReturn(doc)
        `when`(reader.readAll(listOf(7L))).thenReturn(listOf(doc))
        `when`(client.bulk(any(BulkRequest::class.java))).thenReturn(ok)
        val indexer = StorySearchIndexer(client, properties, reader, admin)

        indexer.index(7)
        indexer.indexAll(listOf(7))

        @Suppress("UNCHECKED_CAST")
        val single = (mockingDetails(client).invocations.single { it.method.name == "index" }.arguments[0]
            as java.util.function.Function<IndexRequest.Builder<StorySearchDocument>, ObjectBuilder<IndexRequest<StorySearchDocument>>>)
            .apply(IndexRequest.Builder()).build()
        val captor = ArgumentCaptor.forClass(BulkRequest::class.java)
        verify(client).bulk(captor.capture())
        val bulk = captor.value.operations().single().index<StorySearchDocument>()
        val mapper = StorySearchConfig.searchJsonMapper().objectMapper()
        assertEquals(single.index(), bulk.index())
        assertEquals(single.id(), bulk.id())
        assertEquals(mapper.writeValueAsString(single.document()), mapper.writeValueAsString(bulk.document()))
    }

    @Test
    fun `묶음 색인 리스너는 묶음 진입 전 실패를 삼키고 요청 건수와 예외 이름만 남긴다`() {
        val indexer = mock(StorySearchIndexer::class.java)
        doThrow(IllegalStateException("private index detail")).`when`(indexer).indexAll(listOf(1L, 2L, 3L))

        val logs = warnings(com.knk.manyak.search.event.StorySearchIndexListener::class.java) {
            assertDoesNotThrow {
                com.knk.manyak.search.event.StorySearchIndexListener(indexer)
                    .onStoriesIndexRequested(com.knk.manyak.story.event.StoriesIndexRequestedEvent(listOf(1L, 2L, 3L)))
            }
        }

        assertEquals(listOf("스토리 묶음 색인 중단 (stories=3, error=IllegalStateException)"), logs)
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
