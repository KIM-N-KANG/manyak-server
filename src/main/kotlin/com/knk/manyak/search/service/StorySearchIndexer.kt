package com.knk.manyak.search.service

import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.dto.StorySearchAuthor
import com.knk.manyak.search.dto.StorySearchDocument
import com.knk.manyak.auth.service.AccountQueryService
import com.knk.manyak.image.service.ImageUrlResolver
import com.knk.manyak.story.dto.StorySearchMaterial
import com.knk.manyak.story.service.StorySearchSource
import com.knk.manyak.story.service.StoryTurnCounts
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch.core.BulkRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class StorySearchDocumentReader(
    private val source: StorySearchSource,
    private val turnCounts: StoryTurnCounts,
    private val accounts: AccountQueryService,
    private val images: ImageUrlResolver,
) {
    @Transactional(readOnly = true)
    fun read(storyId: Long): StorySearchDocument? = readAll(listOf(storyId)).firstOrNull()

    /**
     * 여러 스토리의 문서를 한 번에 만든다. 없는 ID는 빠지고 순서는 스토리 ID 오름차순이다. 삭제된 스토리도 `visible=false` 문서를 만든다.
     * 받은 ID를 IN 하나로 읽으므로 호출자가 500건 이하로 나눈다([StorySearchIndexer.BATCH_SIZE]).
     */
    @Transactional(readOnly = true)
    fun readAll(storyIds: Collection<Long>): List<StorySearchDocument> = documents(source.materials(storyIds))

    @Transactional(readOnly = true)
    fun readPage(page: Int): List<StorySearchDocument> =
        documents(source.materialsPage(page, StorySearchIndexer.BATCH_SIZE))

    // 스토리 목록마다 스토리 값(인물, 좋아요 포함), 작성자, 누적 턴 수를 한 번씩 읽는다. 단건, 묶음, 기동 재색인이 모두 이 코드로 문서를 만든다.
    private fun documents(rows: List<StorySearchMaterial>): List<StorySearchDocument> {
        if (rows.isEmpty()) return emptyList()
        val authors = accounts.profilesOf(rows.mapNotNull { it.userId }.toSet())
        val turnCountByStoryId = turnCounts.countByStoryIds(rows.map { it.id })
        return rows.map { story ->
            StorySearchDocument(
                publicId = story.publicId.toString(), title = com.knk.manyak.story.service.UsernameTokenRenderer.render(story.title, story.protagonistName),
                oneLineIntro = com.knk.manyak.story.service.UsernameTokenRenderer.render(story.oneLineIntro.orEmpty(), story.protagonistName),
                genres = story.genre?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
                characterNames = story.characterNames,
                thumbnailUrlSm = images.visibleThumbnailSmUrlFor(story.thumbnailImageUrl, story.thumbnailImageKey, story.thumbnailModerationStatus),
                author = story.userId?.let(authors::get)?.let { StorySearchAuthor(nickname = it.nickname) },
                turnCount = turnCountByStoryId[story.id] ?: 0, likeCount = story.likeCount,
                createdAt = story.createdAt.toEpochMilli(),
                visible = story.publiclyListed,
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

    /**
     * 여러 스토리를 [BATCH_SIZE]건씩 나눠 묶음마다 한 번에 읽고 bulk 요청 한 번으로 색인한다(STORY 4-1-27).
     * 묶음의 읽기나 요청이 실패하면 그 묶음만 건너뛰고 다음 묶음을 진행하며 다시 시도하지 않는다.
     * 로그에는 묶음 건수, 예외 클래스 이름, 실패 건수만 남긴다(STORY 5-4).
     */
    fun indexAll(storyIds: List<Long>) {
        val searchClient = client ?: return
        if (storyIds.isEmpty()) return
        admin.ensureIndex()
        storyIds.chunked(BATCH_SIZE).forEach { chunk ->
            try {
                val documents = reader.readAll(chunk)
                if (documents.isEmpty()) return@forEach
                val failures = bulkIndex(searchClient, documents)
                if (failures > 0) log.warn("스토리 묶음 색인 일부 실패 (stories={}, failed={})", chunk.size, failures)
            } catch (ex: Exception) {
                log.warn("스토리 묶음 색인 실패 (stories={}, error={})", chunk.size, ex.javaClass.simpleName)
            }
        }
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
        } while (documents.size == BATCH_SIZE)
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

    companion object {
        /** 묶음 색인 한 번에 읽고 보내는 최대 건수. 기동 재색인의 페이지 크기와 같다. */
        const val BATCH_SIZE = 500
    }
}
