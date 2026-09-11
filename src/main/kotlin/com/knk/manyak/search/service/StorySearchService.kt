package com.knk.manyak.search.service

import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.dto.StorySearchCursor
import com.knk.manyak.search.dto.StorySearchDocument
import com.knk.manyak.story.dto.StoryPageResponse
import com.knk.manyak.story.repository.StoryRepository
import java.util.UUID
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.OpenSearchException
import org.opensearch.client.opensearch._types.SortOrder
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType
import org.opensearch.client.opensearch.core.SearchRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class StorySearchService(
    private val client: OpenSearchClient?,
    private val properties: StorySearchProperties,
    private val stories: StoryRepository,
    private val indexer: StorySearchIndexer,
) {
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
            if (ex is OpenSearchException && ex.error().type() == "index_not_found_exception") {
                return StoryPageResponse(items = emptyList(), nextCursor = null)
            }
            // 질의 원문이나 자격증명이 포함될 수 있는 외부 오류 본문은 기록하지 않는다.
            log.warn("스토리 검색 실패 (error={})", ex.javaClass.simpleName)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "검색을 일시적으로 사용할 수 없습니다.")
        }
        val hits = response.hits().hits()
        val page = hits.take(pageSize)
        val documents = page.mapNotNull { it.source() }
        val publicIds = documents.mapNotNull { runCatching { UUID.fromString(it.publicId) }.getOrNull() }.distinct()
        // 파생 visible은 지연·실패로 낡을 수 있다. 정본을 한 번 조회해 공개 철회를 응답 전에 차단한다.
        val currentStories = if (publicIds.isEmpty()) emptyList() else stories.findAllByPublicIdIn(publicIds)
        val visibleIds = currentStories.filter { it.isPubliclyListed() }.map { it.publicId.toString() }.toSet()
        currentStories.filterNot { it.isPubliclyListed() }.forEach { story ->
            try {
                // 검색에는 원 트랜잭션이 없으므로 AFTER_COMMIT 이벤트가 아닌 동기 호출로 복구한다.
                indexer.index(story.id)
            } catch (ex: Exception) {
                log.warn("검색 비공개 문서 재색인 실패 (storyId={}, error={})", story.id, ex.javaClass.simpleName)
            }
        }
        return StoryPageResponse(
            items = documents.filter { it.publicId in visibleIds }.map { it.toSummary() },
            // 필터 전 마지막 hit로 진행한다. 전부 제외돼 빈 페이지여도 다음 후보를 계속 탐색할 수 있다.
            nextCursor = if (hits.size > pageSize) StorySearchCursor.fromSort(page.last().sort()).encode(query) else null,
        )
    }
}
