package com.knk.manyak.push.outbox

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.dto.CreateSimpleStoryRequest
import com.knk.manyak.story.entity.*
import com.knk.manyak.story.repository.*
import com.knk.manyak.story.service.SimpleStoryCreationService
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** H2에서는 실제 완성·replay와 동기 기록 호출의 트랜잭션 경계를 확인하고 SQL 자체는 PG 테스트로 검증한다. */
@ActiveProfiles("test")
@SpringBootTest(properties = ["manyak.ai.story.stub=true", "manyak.push.mode=remote"])
class StoryCompletionOutboxIntegrationTests {
    @MockitoBean private lateinit var store: PushOutboxStore
    @MockitoBean private lateinit var relay: PushOutboxRelay
    @Autowired private lateinit var service: SimpleStoryCreationService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var sessions: StoryCreationSessionRepository
    @Autowired private lateinit var storylines: StoryCreationStorylineRepository
    @Autowired private lateinit var requests: StoryCreationRequestRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner
    private lateinit var user: User
    private lateinit var request: CreateSimpleStoryRequest
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        reset(store)
        user = users.save(User(nickname = "제작자", status = UserStatus.ACTIVE, servicePushEnabled = false))
        val session = sessions.save(StoryCreationSession(userId = user.id, status = StoryCreationSessionStatus.STORYLINES_GENERATED))
        val storyline = storylines.save(StoryCreationStoryline(creationSession = session, storylineText = "예시 스토리라인", storylineOrder = 1))
        request = CreateSimpleStoryRequest(requestId = UUID.randomUUID(), simpleCreationId = session.id, storylineId = storyline.id, additionalInfos = emptyList())
    }
    @Test fun `완성 마킹 트랜잭션에서 기록하고 replay와 폴백에서는 다시 기록하지 않는다`() {
        var calls = 0
        doAnswer { invocation ->
            calls++
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            assertThat(requests.findByRequestId(request.requestId)!!.status).isEqualTo(StoryCreationRequestStatus.COMPLETED)
            val message = invocation.getArgument<PushMessage>(0)
            assertThat(message.messageId).isEqualTo("story-completed:${request.requestId}")
            assertThat(message.recipientId).isEqualTo(user.publicId.toString())
            null
        }.`when`(store).insert(any(PushMessage::class.java) ?: placeholder(), any(java.time.Instant::class.java) ?: java.time.Instant.EPOCH)
        service.createSimpleStory(request, user.id)
        service.createSimpleStory(request, user.id)
        val completed = requests.findByRequestId(request.requestId)!!
        completed.resultJson = """{"legacy":true}"""
        requests.saveAndFlush(completed)
        service.createSimpleStory(request, user.id)
        assertThat(calls).isEqualTo(1)
    }
    @Test fun `기록 실패는 COMPLETED 마킹을 롤백한다`() {
        doThrow(IllegalStateException("outbox insert failed")).`when`(store)
            .insert(any(PushMessage::class.java) ?: placeholder(), any(java.time.Instant::class.java) ?: java.time.Instant.EPOCH)
        assertThatThrownBy { service.createSimpleStory(request, user.id) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(requests.findByRequestId(request.requestId)!!.status).isEqualTo(StoryCreationRequestStatus.PENDING)
    }
    private fun placeholder() = PushMessage("", "", data = emptyMap(), requestId = "", sessionId = "")
}
