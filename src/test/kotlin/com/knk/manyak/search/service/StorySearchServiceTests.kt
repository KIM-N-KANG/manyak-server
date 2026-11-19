package com.knk.manyak.search.service

import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.dto.StorySearchAuthor
import com.knk.manyak.search.dto.StorySearchCursor
import com.knk.manyak.search.dto.StorySearchDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.knk.manyak.search.config.StorySearchConfig
import org.opensearch.client.opensearch._types.FieldValue
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.mockito.Mockito
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.OpenSearchException
import org.opensearch.client.opensearch._types.ErrorResponse
import org.opensearch.client.opensearch.core.SearchRequest
import com.knk.manyak.story.service.StoryAuthorResolver
import com.knk.manyak.story.service.OfficialStoryAccount
import com.knk.manyak.story.service.StoryService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.knk.manyak.story.repository.StoryRepository
import java.io.StringWriter
import java.util.UUID

class StorySearchServiceTests {
    @Test
    fun `검색 질의는 기존 필드와 장르 포함 조건 중 하나를 요구하고 특수문자를 이스케이프한다`() {
        val client = Mockito.mock(OpenSearchClient::class.java)
        val service = StorySearchService(
            client, StorySearchProperties(), storyPlayReaderOver(Mockito.mock(StoryRepository::class.java)),
            Mockito.mock(StorySearchIndexer::class.java), Mockito.mock(OfficialStoryAccount::class.java),
            Mockito.mock(StoryAuthorResolver::class.java), Mockito.mock(StoryService::class.java),
        )
        val requests = mutableListOf<SearchRequest>()
        Mockito.`when`(client.search(Mockito.any(SearchRequest::class.java), Mockito.eq(StorySearchDocument::class.java)))
            .thenAnswer {
                requests += it.getArgument<SearchRequest>(0)
                throw OpenSearchException(ErrorResponse.Builder().status(404)
                    .error { e -> e.type("index_not_found_exception").reason("test") }.build())
            }
        for ((query, pattern) in listOf(
            " 탈출 " to "*탈출*", "bl" to "*bl*", "탈*" to "*탈\\**",
            "탈?" to "*탈\\?*", "탈\\*?" to "*탈\\\\\\*\\?*",
        )) {
            service.search(query, 20, null)
            val bool = requests.last().query()!!.bool()
            assertEquals("1", bool.minimumShouldMatch())
            assertEquals(2, bool.should().size)
            val multiMatch = bool.should().single { it.isMultiMatch }.multiMatch()
            assertEquals(query.trim(), multiMatch.query())
            assertEquals(listOf("title^3", "oneLineIntro", "genres", "characterNames"), multiMatch.fields())
            val wildcard = bool.should().single { it.isWildcard }.wildcard()
            assertEquals("genres", wildcard.field())
            assertEquals(pattern, wildcard.value())
            assertEquals(true, wildcard.caseInsensitive())
            val visible = bool.filter().single().term()
            assertEquals("visible", visible.field())
            assertTrue(visible.value().booleanValue())
        }
    }

    @Test
    fun `없는 인덱스와 저장소 오류는 커서 없는 요청이면 DB 대체 검색 한 페이지로, 커서 있는 요청이면 503으로 응답한다`() {
        val client = Mockito.mock(OpenSearchClient::class.java)
        val stories = Mockito.mock(StoryRepository::class.java)
        val indexer = Mockito.mock(StorySearchIndexer::class.java)
        val fallback = Mockito.mock(StoryService::class.java)
        val meters = SimpleMeterRegistry()
        val service = StorySearchService(
            client, StorySearchProperties(), storyPlayReaderOver(stories), indexer,
            Mockito.mock(OfficialStoryAccount::class.java), Mockito.mock(StoryAuthorResolver::class.java), fallback, meters,
        )
        // 기동 시 0으로 등록한다.
        assertEquals(0.0, meters.counter("manyak.story.search.fallback").count())
        val card = StorySearchDocument(publicId = UUID.randomUUID().toString(), title = "왕국").toSummary(isOriginal = false)
        Mockito.`when`(fallback.searchListedByText("왕국", 20)).thenReturn(listOf(card))
        fun failure(type: String, status: Int) = OpenSearchException(
            ErrorResponse.Builder()
                .status(status).error { it.type(type).reason("test") }.build(),
        )
        val cursor = StorySearchCursor.fromSort(listOf(FieldValue.of(1.0), FieldValue.of(1L), FieldValue.of(UUID.randomUUID().toString()))).encode("왕국")
        val errors = listOf(failure("index_not_found_exception", 404), failure("security_exception", 403),
            failure("search_phase_execution_exception", 500), java.io.IOException("unavailable"))
        for ((i, error) in errors.withIndex()) {
            Mockito.doThrow(error).`when`(client).search(
                Mockito.any(SearchRequest::class.java),
                Mockito.eq(StorySearchDocument::class.java),
            )
            val page = service.search(" 왕국 ", 20, null)
            assertEquals(listOf(card), page.items)
            assertNull(page.nextCursor)
            assertEquals(i + 1.0, meters.counter("manyak.story.search.fallback").count())
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException::class.java) {
                service.search("왕국", 20, cursor)
            }.statusCode)
        }
        assertEquals(errors.size.toDouble(), meters.counter("manyak.story.search.fallback").count())
        Mockito.verifyNoInteractions(stories, indexer)
    }

    @Test
    fun `응답 한도를 넘긴 질의는 기다리지 않고 DB 대체 검색으로 응답한다`() {
        val client = Mockito.mock(OpenSearchClient::class.java)
        val fallback = Mockito.mock(StoryService::class.java)
        Mockito.`when`(fallback.searchListedByText("왕국", 20)).thenReturn(emptyList())
        val service = StorySearchService(
            client, StorySearchProperties(queryTimeout = java.time.Duration.ofMillis(200)),
            storyPlayReaderOver(Mockito.mock(StoryRepository::class.java)), Mockito.mock(StorySearchIndexer::class.java),
            Mockito.mock(OfficialStoryAccount::class.java), Mockito.mock(StoryAuthorResolver::class.java), fallback,
        )
        Mockito.`when`(client.search(Mockito.any(SearchRequest::class.java), Mockito.eq(StorySearchDocument::class.java)))
            .thenAnswer { Thread.sleep(5_000); null }
        val started = System.nanoTime()
        val page = service.search("왕국", 20, null)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(page.items.isEmpty())
        assertTrue(elapsedMillis < 2_000, "한도 200ms인데 ${elapsedMillis}ms 걸림")
        Mockito.verify(fallback).searchListedByText("왕국", 20)
    }

    @Test
    fun `미설정 검색은 503이고 유효하지 않은 입력은 먼저 400이다`() {
        val service = StorySearchService(
            null, StorySearchProperties(),
            storyPlayReaderOver(Mockito.mock(StoryRepository::class.java)),
            Mockito.mock(StorySearchIndexer::class.java),
            Mockito.mock(OfficialStoryAccount::class.java),
            Mockito.mock(StoryAuthorResolver::class.java), Mockito.mock(StoryService::class.java),
        )
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException::class.java) {
            service.search("왕국", 20, null)
        }.statusCode)
        for (q in listOf("", " ", "가", "가".repeat(101))) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException::class.java) {
                service.search(q, 20, null)
            }.statusCode)
        }
    }

    @Test
    fun `커서는 세 정렬값을 보존하고 다른 검색어와 깨진 값을 거부한다`() {
        val cursor = StorySearchCursor(2.5, 1780000000000, UUID.randomUUID())
        assertEquals(cursor, StorySearchCursor.decode(cursor.encode("왕국"), "왕국"))
        for (raw in listOf(cursor.encode("왕국"), "???", "", java.util.Base64.getUrlEncoder().encodeToString("bad|NaN|1|no".toByteArray()))) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException::class.java) {
                StorySearchCursor.decode(raw, "마법")
            }.statusCode)
        }
        assertEquals(listOf(FieldValue.of(2.5), FieldValue.of(1780000000000), FieldValue.of(cursor.publicId.toString())), cursor.sortValues())
    }

    @Test
    fun `OpenSearch 전용 Jackson 매퍼는 카드 문서를 왕복하고 Spring 빈을 필요로 하지 않는다`() {
        val mapper = StorySearchConfig.searchJsonMapper()
        val document = StorySearchDocument(publicId = UUID.randomUUID().toString(), title = "왕국", author = StorySearchAuthor(nickname = "작가"), visible = true)
        val writer = StringWriter()
        mapper.jsonProvider().createGenerator(writer).use { mapper.serialize(document, it) }
        val decoded = mapper.jsonProvider().createParser(writer.toString().reader()).use {
            mapper.deserialize(it, StorySearchDocument::class.java)
        }
        assertEquals(document, decoded)
        assertNull(decoded.toSummary(isOriginal = false).author!!.id)
    }
}

/** 저장소 목 위에 실제 스토리 조회 서비스를 얹는다. 검색은 공개 ID 묶음 조회만 쓴다. */
internal fun storyPlayReaderOver(stories: StoryRepository) = com.knk.manyak.story.service.StoryPlayReader(
    stories,
    Mockito.mock(com.knk.manyak.story.repository.StoryStartSettingRepository::class.java),
    Mockito.mock(com.knk.manyak.story.repository.StorySuggestedInputRepository::class.java),
    Mockito.mock(com.knk.manyak.story.repository.StoryEndingRepository::class.java),
    Mockito.mock(com.knk.manyak.story.repository.StoryMainEventRepository::class.java),
    Mockito.mock(com.knk.manyak.story.repository.StoryCreationSessionRepository::class.java),
)
