package com.knk.manyak.story.controller

import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.search.service.StorySearchIndexer
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.dto.StoryCreationSessionStatus
import com.knk.manyak.story.dto.StoryCreationTagSource
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.MockedBoundaryIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.after
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 간편 제작 완성의 검색 색인 요청 시점(KNK-2056). 색인 이벤트는 완성 저장 트랜잭션 안에서 발행되고, 리스너는 커밋 뒤에만 돈다
 * (`@TransactionalEventListener(AFTER_COMMIT)`, `fallbackExecution` 없음). 발행이 트랜잭션 밖으로 밀리면 색인이 조용히 빠진다.
 */
class SimpleStoryCompletionIndexIntegrationTests : MockedBoundaryIntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var indexer: StorySearchIndexer
    @Autowired private lateinit var aiCalls: AiCallRecorder
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var sessions: StoryCreationSessionRepository
    @Autowired private lateinit var sessionTags: StoryCreationSessionTagRepository
    @Autowired private lateinit var storylines: StoryCreationStorylineRepository
    @Autowired private lateinit var tags: StoryCreationTagRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner

    @BeforeEach fun setUp() {
        cleaner.cleanAll()
        reset(indexer)
    }

    @Test fun `완성이 커밋되면 저장된 스토리를 한 번 색인한다`() {
        val storyline = storyline()
        val committed = AtomicReference<Boolean>()
        // 리스너 스레드에서 읽으므로 커밋된 행만 보인다.
        doAnswer { committed.set(stories.existsById(it.getArgument(0))); null }.`when`(indexer).index(anyLong())

        post(storyline).expectStatus().isCreated

        val story = stories.findAll().single()
        verify(indexer, timeout(2000).times(1)).index(story.id)
        verify(indexer, after(300).times(1)).index(anyLong())
        assertThat(committed.get()).isTrue()
    }

    @Test fun `색인 요청을 발행한 뒤 저장 트랜잭션이 롤백되면 색인하지 않는다`() {
        val storyline = storyline()
        // 발행 다음 단계(호출 기록에 스토리 연결)를 실패시켜, 발행은 일어났지만 커밋되지 않는 저장을 만든다.
        doThrow(IllegalStateException("호출 기록 연결 실패(테스트)")).`when`(aiCalls).attachStoryId(anyLong(), anyLong())

        post(storyline).expectStatus().is5xxServerError

        assertThat(stories.findAll()).isEmpty()
        verify(indexer, after(500).never()).index(anyLong())
    }

    private fun storyline(): StoryCreationStoryline {
        val session = sessions.save(StoryCreationSession(userId = null, status = StoryCreationSessionStatus.STORYLINES_GENERATED))
        val genre = tags.save(StoryCreationTag(category = SimpleStoryTagCategory.GENRE, name = "로맨스",
            tagSource = StoryCreationTagSource.PREDEFINED, sortOrder = 0, isActive = true))
        sessionTags.save(StoryCreationSessionTag(creationSession = session, tag = genre))
        return storylines.save(StoryCreationStoryline(creationSession = session, storylineText = "예시 스토리라인", storylineOrder = 1))
    }

    private fun post(storyline: StoryCreationStoryline): RestTestClient.ResponseSpec = client.post().uri("/api/v1/stories/simple")
        .header("X-Manyak-Device-Id", "index-device")
        .contentType(MediaType.APPLICATION_JSON)
        .body("""{"requestId":"${UUID.randomUUID()}","simpleCreationId":${storyline.creationSession.id},"storylineId":${storyline.id},"additionalInfos":[]}""")
        .exchange()
}
