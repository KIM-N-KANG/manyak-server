package com.knk.manyak.search

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.image.service.ImageUrlResolver
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryLikeRepository
import com.knk.manyak.story.repository.StoryRepository
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
    private val chats: StoryChatRepository,
    private val users: UserRepository,
    private val images: ImageUrlResolver,
) {
    @Transactional(readOnly = true)
    fun read(storyId: Long): StorySearchDocument? = stories.findById(storyId).orElse(null)?.let(::document)

    @Transactional(readOnly = true)
    fun readPage(page: Int): List<StorySearchDocument> =
        stories.findAll(PageRequest.of(page, 500, Sort.by("id"))).content.map(::document)

    private fun document(story: Story): StorySearchDocument = StorySearchDocument(
        publicId = story.publicId.toString(), title = story.title, oneLineIntro = story.oneLineIntro.orEmpty(),
        genres = story.genre?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
        characterNames = characters.findByStoryIdOrderByIdAsc(story.id).map { it.name },
        thumbnailUrlSm = images.visibleThumbnailSmUrlFor(story.thumbnailImageUrl, story.thumbnailImageKey, story.thumbnailModerationStatus),
        author = story.userId?.let { users.findById(it).orElse(null) }?.let { StorySearchAuthor(nickname = it.nickname) },
        turnCount = chats.sumCurrentTurnByStoryId(story.id), likeCount = likes.countByStoryId(story.id),
        createdAt = story.createdAt.toEpochMilli(),
        visible = story.isPubliclyListed(),
    )
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
            val request = BulkRequest.Builder()
            documents.forEach { document ->
                request.operations { op -> op.index { it.index(properties.storyIndex).id(document.publicId).document(document) } }
            }
            val result = searchClient.bulk(request.build())
            val failures = result.items().count { it.error() != null || it.status() >= 300 }
            indexed += documents.size - failures
            failed += failures
            if (failures > 0) log.warn("스토리 bulk 색인 일부 실패 (page={}, failed={})", page, failures)
        } while (documents.size == 500)
        log.info("스토리 재색인 완료 (indexed={}, failed={})", indexed, failed)
    }
}
