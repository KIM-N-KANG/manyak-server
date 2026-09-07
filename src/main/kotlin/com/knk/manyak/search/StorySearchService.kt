package com.knk.manyak.search

import com.knk.manyak.story.dto.StoryPageResponse
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.SortOrder
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType
import org.opensearch.client.opensearch.core.SearchRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class StorySearchService(private val client: OpenSearchClient?, private val properties: StorySearchProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun search(q: String, limit: Int, cursor: String?): StoryPageResponse {
        val query = q.trim()
        if (query.length !in 2..100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어는 2~100자여야 합니다.")
        }
        val after = cursor?.let { StorySearchCursor.decode(it, query) }
        val searchClient = client ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "검색이 설정되지 않았습니다.")
        val pageSize = limit.coerceIn(1, 50)
        val request = SearchRequest.Builder().index(properties.storyIndex).size(pageSize + 1)
            .query { it.bool { b ->
                b.must { m -> m.multiMatch { mm ->
                    mm.query(query).fields("title^3", "oneLineIntro", "genres", "characterNames").type(TextQueryType.BestFields)
                } }.filter { f -> f.term { t -> t.field("visible").value { v -> v.booleanValue(true) } } }
            } }
            .sort { it.score { s -> s.order(SortOrder.Desc) } }
            .sort { it.field { f -> f.field("createdAt").order(SortOrder.Desc) } }
            .sort { it.field { f -> f.field("publicId").order(SortOrder.Asc) } }
        after?.let { request.searchAfter(it.sortValues()) }
        val response = try {
            searchClient.search(request.build(), StorySearchDocument::class.java)
        } catch (ex: Exception) {
            // 질의 원문이나 자격증명이 포함될 수 있는 외부 오류 본문은 기록하지 않는다.
            log.warn("스토리 검색 실패 (error={})", ex.javaClass.simpleName)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "검색을 일시적으로 사용할 수 없습니다.")
        }
        val hits = response.hits().hits()
        val page = hits.take(pageSize)
        return StoryPageResponse(
            items = page.map { requireNotNull(it.source()).toSummary() },
            nextCursor = if (hits.size > pageSize) StorySearchCursor.fromSort(page.last().sort()).encode(query) else null,
        )
    }
}
