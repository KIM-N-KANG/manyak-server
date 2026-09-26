package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.outbox.PushOutboxStore
import com.knk.manyak.push.outbox.PushMessage
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.util.concurrent.Executor

@ActiveProfiles("test")
@SpringBootTest(properties = ["manyak.push.mode=remote"])
class ModerationOutboxIntegrationTests {
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var submissions: StorySubmissionRepository
    @Autowired private lateinit var service: StorySubmissionService
    @Autowired private lateinit var worker: SubmissionTransactions
    @MockitoBean(name = "storyModerationExecutor") private lateinit var executor: Executor
    @MockitoBean private lateinit var relay: com.knk.manyak.push.outbox.PushOutboxRelay
    @MockitoBean private lateinit var store: PushOutboxStore
    @BeforeEach fun reset() = cleaner.cleanAll()

    @Test fun `종료와 remote 아웃박스는 한 트랜잭션이고 회차 멱등키를 사용한다`() {
        val messages = mutableListOf<PushMessage>()
        Mockito.doAnswer { call -> messages.add(call.getArgument(0)); null }.`when`(store).insert(anyMessage(), anyInstant())
        val user = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = user.id, title = "원본"))
        service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정"), user.id)
        val row = submissions.findAll().single()
        worker.finish(row.id, 1, ModerationResult("APPROVED", emptyList(), null))
        assertEquals("수정", stories.findById(story.id).orElseThrow().title)
        assertEquals("story-moderation:${row.publicId}:1", messages.single().messageId)
        assertEquals("STORY_MODERATION_COMPLETED", messages.single().type)
        assertEquals("SERVICE", messages.single().kind)
        assertEquals(user.publicId.toString(), messages.single().recipientId)
        assertEquals("APPROVED", messages.single().data["status"])
        worker.finish(row.id, 1, ModerationResult("APPROVED", emptyList(), null))
        assertEquals(1, messages.size)
    }

    @Test fun `아웃박스 DB 장애이면 라이브와 승인 상태가 함께 롤백된다`() {
        Mockito.doThrow(org.springframework.dao.TransientDataAccessResourceException("database unavailable"))
            .`when`(store).insert(anyMessage(), anyInstant())
        val user = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = user.id, title = "원본"))
        service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정"), user.id)
        val row = submissions.findAll().single()
        assertThrows(org.springframework.dao.TransientDataAccessResourceException::class.java) {
            worker.finish(row.id, 1, ModerationResult("APPROVED", emptyList(), null))
        }
        assertEquals("원본", stories.findById(story.id).orElseThrow().title)
        assertEquals(SubmissionStatus.PENDING, submissions.findById(row.id).orElseThrow().status)
    }
    // Mockito 매처가 반환하는 null을 Kotlin non-null 검사에 전달하지 않는다.
    private fun anyMessage(): PushMessage = Mockito.any(PushMessage::class.java) ?: PushMessage("", "", data = emptyMap(), requestId = "", sessionId = "")
    private fun anyInstant(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH
}
