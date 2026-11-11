package com.knk.manyak.search.service

import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.dto.StorySearchAuthor
import com.knk.manyak.search.dto.StorySearchDocument
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.image.service.ImageUrlResolver
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryLikeRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.StoryTurnCounts
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch.core.BulkRequest
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class StorySearchDocumentReader(
    private val stories: StoryRepository,
    private val characters: StoryCharacterRepository,
    private val likes: StoryLikeRepository,
    private val turnCounts: StoryTurnCounts,
    private val users: UserRepository,
    private val images: ImageUrlResolver,
) {
    @Transactional(readOnly = true)
    fun read(storyId: Long): StorySearchDocument? = readAll(listOf(storyId)).firstOrNull()

    /** 여러 스토리의 문서를 한 번에 만든다. 없는 ID는 빠지고 순서는 스토리 ID 오름차순이다. 삭제된 스토리도 `visible=false` 문서를 만든다. */
    @Transactional(readOnly = true)
    fun readAll(storyIds: Collection<Long>): List<StorySearchDocument> = documents(stories.findAllById(storyIds).sortedBy { it.id })

    @Transactional(readOnly = true)
    fun readPage(page: Int): List<StorySearchDocument> =
        documents(stories.findAll(PageRequest.of(page, 500, Sort.by("id"))).content)

    // 스토리 목록마다 인물, 작성자, 누적 턴 수, 좋아요 수를 한 번씩 읽는다. 단건, 묶음, 기동 재색인이 모두 이 코드로 문서를 만든다.
    private fun documents(rows: List<Story>): List<StorySearchDocument> {
        if (rows.isEmpty()) return emptyList()
        val storyIds = rows.map { it.id }
        val characterNames = characters.findByStoryIdInOrderByStoryIdAscIdAsc(storyIds).groupBy({ it.story.id }, { it.name })
        val authors = users.findAllById(rows.mapNotNull { it.userId }.toSet()).associateBy { it.id }
        val turnCountByStoryId = turnCounts.countByStoryIds(storyIds)
        val likeCountByStoryId = likes.countByStoryIds(storyIds).associate { it.storyId to it.likeCount }
        return rows.map { story ->
            StorySearchDocument(
                publicId = story.publicId.toString(), title = com.knk.manyak.story.service.UsernameTokenRenderer.render(story.title, story.protagonistName),
                oneLineIntro = com.knk.manyak.story.service.UsernameTokenRenderer.render(story.oneLineIntro.orEmpty(), story.protagonistName),
                genres = story.genre?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
                characterNames = characterNames[story.id].orEmpty(),
                thumbnailUrlSm = images.visibleThumbnailSmUrlFor(story.thumbnailImageUrl, story.thumbnailImageKey, story.thumbnailModerationStatus),
                author = story.userId?.let(authors::get)?.let { StorySearchAuthor(nickname = it.nickname) },
                turnCount = turnCountByStoryId[story.id] ?: 0, likeCount = likeCountByStoryId[story.id] ?: 0,
                createdAt = story.createdAt.toEpochMilli(),
                visible = story.isPubliclyListed(),
            )
        }
    }
}

@Component
class StorySearchIndexer(
    private val client: OpenSearchClient?,
    private val properties: StorySearchProperties,
    private val reader: StorySearchDocumentReader,
    private val admin: StorySearchIndexAdmin,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun index(storyId: Long) {
        val searchClient = client ?: return
        val document = reader.read(storyId) ?: return
        admin.ensureIndex()
        searchClient.index { it.index(properties.storyIndex).id(document.publicId).document(document) }
    }

    fun reindex() {
        val searchClient = client ?: return
        admin.ensureIndex()
        var page = 0
        var indexed = 0
        var failed = 0
        do {
            val documents = reader.readPage(page++)
            if (documents.isEmpty()) break
            val failures = bulkIndex(searchClient, documents)
            indexed += documents.size - failures
            failed += failures
            if (failures > 0) log.warn("스토리 bulk 색인 일부 실패 (page={}, failed={})", page, failures)
        } while (documents.size == 500)
        log.info("스토리 재색인 완료 (indexed={}, failed={})", indexed, failed)
    }

    /** 문서들을 bulk 요청 한 번으로 색인하고 항목 실패 수를 돌려준다. */
    private fun bulkIndex(searchClient: OpenSearchClient, documents: List<StorySearchDocument>): Int {
        val request = BulkRequest.Builder()
        documents.forEach { document ->
            request.operations { op -> op.index { it.index(properties.storyIndex).id(document.publicId).document(document) } }
        }
        val result = searchClient.bulk(request.build())
        return result.items().count { it.error() != null || it.status() >= 300 }
    }
}
