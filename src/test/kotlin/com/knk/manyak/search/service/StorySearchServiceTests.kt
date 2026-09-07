package com.knk.manyak.search.service

import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.dto.StorySearchAuthor
import com.knk.manyak.search.dto.StorySearchCursor
import com.knk.manyak.search.dto.StorySearchDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.opensearch.client.json.jsonb.JsonbJsonpMapper
import org.opensearch.client.opensearch._types.FieldValue
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.io.StringWriter
import java.util.UUID

class StorySearchServiceTests {
    @Test
    fun `미설정 검색은 503이고 유효하지 않은 입력은 먼저 400이다`() {
        val service = StorySearchService(
            null, StorySearchProperties(),
            org.mockito.Mockito.mock(com.knk.manyak.story.repository.StoryRepository::class.java),
            org.mockito.Mockito.mock(StorySearchIndexer::class.java),
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
    fun `JSON B 매퍼는 카드 문서를 왕복하고 Spring Jackson 빈을 필요로 하지 않는다`() {
        val mapper = JsonbJsonpMapper()
        val document = StorySearchDocument(publicId = UUID.randomUUID().toString(), title = "왕국", author = StorySearchAuthor(nickname = "작가"), visible = true)
        val writer = StringWriter()
        mapper.jsonProvider().createGenerator(writer).use { mapper.serialize(document, it) }
        val decoded = mapper.jsonProvider().createParser(writer.toString().reader()).use {
            mapper.deserialize(it, StorySearchDocument::class.java)
        }
        assertEquals(document, decoded)
        assertNull(decoded.toSummary().author!!.id)
    }
}
