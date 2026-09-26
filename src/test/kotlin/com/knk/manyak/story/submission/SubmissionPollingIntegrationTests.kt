package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@ActiveProfiles("test")
@SpringBootTest
class SubmissionPollingIntegrationTests {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var rows: StorySubmissionRepository
    @Autowired lateinit var service: StorySubmissionService
    @Autowired lateinit var cleaner: DatabaseCleaner
    @MockitoBean(name = "storyModerationExecutor") lateinit var executor: Executor
    @BeforeEach fun clean() = cleaner.cleanAll()
    @Test fun `제출은 실행기에 직접 전달하지 않고 미선점으로 남긴다`() {
        val user = users.save(User(nickname = "작가"))
        repeat(6) {
            val story = stories.save(Story(userId = user.id, title = "원본"))
            service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정"), user.id)
        }
        Mockito.verifyNoInteractions(executor)
        assertTrue(rows.findAll().all { it.dispatchedAt == null })
    }
    @Test fun `검수 실행기는 대기 큐를 두지 않는다`() {
        val pool = ModerationExecutionConfig().executor(2) as ThreadPoolTaskExecutor
        try { assertEquals(0, pool.threadPoolExecutor.queue.remainingCapacity()) }
        finally { pool.shutdown() }
    }
    @Autowired lateinit var poller: SubmissionPoller
    @Autowired lateinit var claims: SubmissionClaimStore
    @Autowired lateinit var transactions: SubmissionTransactions

    @Test fun `슬롯 수만 선점하고 실행 중에는 임대 시각이 지나도 대기를 증폭하지 않는다`() {
        val user = users.save(User(nickname = "작가"))
        repeat(6) {
            val story = stories.save(Story(userId = user.id, title = "원본 $it"))
            service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정 $it"), user.id)
        }
        val queued = mutableListOf<Runnable>()
        Mockito.doAnswer { call -> queued.add(call.getArgument(0)); null }.`when`(executor).execute(Mockito.any(Runnable::class.java))
        try {
            poller.poll()
            assertEquals(4, queued.size)
            assertEquals(4, rows.findAll().count { it.dispatchedAt != null })
            val waiting = rows.findAll().filter { it.dispatchedAt == null }.map { it.id }.toSet()
            assertEquals(2, waiting.size)
            rows.findAll().filter { it.dispatchedAt != null }.forEach {
                it.dispatchedAt = java.time.Instant.now().minusSeconds(301)
                rows.save(it)
            }
            poller.poll()
            assertEquals(4, queued.size)
            assertTrue(rows.findAll().filter { it.id in waiting }.all { it.dispatchedAt == null && it.attempt == 1 })
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            poller.poll()
            assertEquals(2, queued.size)
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertTrue(rows.findAll().all { it.status == SubmissionStatus.APPROVED })
        } finally { while (queued.isNotEmpty()) queued.removeAt(0).run() }
    }

    @Test fun `재제출은 선점 회차를 무효화하고 새로운 선점 전에는 NULL이다`() {
        val user = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = user.id, title = "원본"))
        service.update(story.publicId.toString(), UpdateStoryRequest(title = "첫 입력"), user.id)
        val first = claims.claim(java.time.Instant.now(), java.time.Duration.ofSeconds(300), 1).single()
        transactions.fail(first.id, first.attempt, "MODERATION_UNAVAILABLE")
        service.update(story.publicId.toString(), UpdateStoryRequest(title = "새 입력"), user.id)
        val retry = rows.findById(first.id).orElseThrow()
        assertNull(retry.dispatchedAt)
        assertEquals(first.attempt + 1, retry.attempt)
        transactions.finish(first.id, first.attempt, ModerationResult("APPROVED", emptyList(), null))
        assertEquals("원본", stories.findById(story.id).orElseThrow().title)
        val next = claims.claim(java.time.Instant.now(), java.time.Duration.ofSeconds(300), 1).single()
        assertEquals(retry.attempt + 1, next.attempt)
        transactions.finish(next.id, next.attempt, ModerationResult("APPROVED", emptyList(), null))
        assertEquals("새 입력", stories.findById(story.id).orElseThrow().title)
    }

    @Test fun `임대가 AI 타임아웃 이하이면 기동 설정을 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            SubmissionPoller(claims, Mockito.mock(SubmissionExecutor::class.java), executor, 4,
                java.time.Duration.ofSeconds(180), java.time.Duration.ofSeconds(180), false)
        }
    }

}
