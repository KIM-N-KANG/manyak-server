package com.knk.manyak.global.observability.analytics

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.MdcKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.slf4j.MDC
import java.util.Optional
import java.util.UUID

/**
 * [ServerAnalytics] 파사드가 이벤트명·프로퍼티·식별자를 스펙(§6-2·§6-4·§6-6-7)대로 구성해 싱크로 넘기는지 고정한다.
 * 발행 싱크는 이벤트를 캡처만 하는 가짜로 대체하고, 식별자 컨텍스트는 MDC로 주입한다.
 */
class ServerAnalyticsTest {

    private class CapturingPublisher : AnalyticsEventPublisher {
        val events = mutableListOf<AnalyticsEvent>()
        override fun publish(event: AnalyticsEvent) {
            events.add(event)
        }
    }

    private val publisher = CapturingPublisher()
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val analytics = ServerAnalytics(publisher, userRepository)

    private val memberPublicId = UUID.randomUUID()

    private fun seedMember(userId: Long) {
        val user = User(id = userId, nickname = "회원", publicId = memberPublicId)
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
    }

    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `회원 요청은 user_id에 public_id를 싣고 is_logged_in true다`() {
        seedMember(42L)
        MDC.put(MdcKeys.DEVICE_ID_HASH, "device_hash_abc123")
        MDC.put(MdcKeys.REQUEST_ID, "req_zzz")

        analytics.storylineGenerationSucceeded(userId = 42L, creationId = 7L)

        val e = publisher.events.single()
        assertThat(e.eventType).isEqualTo("server_storyCreate_storyGeneration_processed_succeeded")
        assertThat(e.userId).isEqualTo(memberPublicId.toString())
        assertThat(e.eventProperties["creation_id"]).isEqualTo(7L)
        assertThat(e.eventProperties["is_logged_in"]).isEqualTo(true)
        assertThat(e.eventProperties["request_id"]).isEqualTo("req_zzz")
        assertThat(e.eventProperties["device_id_hash"]).isEqualTo("device_hash_abc123")
        assertThat(e.insertId).isEqualTo("req_zzz:server_storyCreate_storyGeneration_processed_succeeded")
    }

    @Test
    fun `게스트 요청은 user_id가 null이고 device_id에 device_id_hash를 쓴다`() {
        MDC.put(MdcKeys.DEVICE_ID_HASH, "device_hash_guest")

        analytics.storylineGenerationSucceeded(userId = null, creationId = 3L)

        val e = publisher.events.single()
        assertThat(e.userId).isNull()
        assertThat(e.deviceId).isEqualTo("device_hash_guest")
        assertThat(e.eventProperties["is_logged_in"]).isEqualTo(false)
    }

    @Test
    fun `device 헤더가 없으면 게스트 device_id는 unknown이고 상관 프로퍼티에는 넣지 않는다`() {
        MDC.put(MdcKeys.DEVICE_ID_HASH, "unknown")

        analytics.feedbackSubmissionSucceeded(userId = null)

        val e = publisher.events.single()
        assertThat(e.eventType).isEqualTo("server_feedback_submission_processed_succeeded")
        assertThat(e.deviceId).isEqualTo("unknown")
        assertThat(e.eventProperties).doesNotContainKey("device_id_hash")
    }

    @Test
    fun `채팅 실패는 error_type과 is_regenerated를 싣고 error_code를 매핑한다`() {
        analytics.chatAiMessageFailed(
            userId = null,
            chatId = "chat-1",
            turnNumber = 4,
            errorCode = "provider_timeout",
            isRegenerated = true,
        )

        val e = publisher.events.single()
        assertThat(e.eventType).isEqualTo("server_chat_aiMessage_processed_failed")
        assertThat(e.eventProperties["chat_id"]).isEqualTo("chat-1")
        assertThat(e.eventProperties["turn_number"]).isEqualTo(4)
        assertThat(e.eventProperties["error_type"]).isEqualTo("network")
        assertThat(e.eventProperties["is_regenerated"]).isEqualTo(true)
    }

    @Test
    fun `채팅 성공은 ending_id가 있을 때만 싣는다`() {
        analytics.chatAiMessageSucceeded(userId = null, chatId = "c", turnNumber = 1, isRegenerated = false)
        analytics.chatAiMessageSucceeded(userId = null, chatId = "c", turnNumber = 2, isRegenerated = false, endingId = "end-9")

        assertThat(publisher.events[0].eventProperties).doesNotContainKey("ending_id")
        assertThat(publisher.events[1].eventProperties["ending_id"]).isEqualTo("end-9")
    }

    @Test
    fun `로그인 성공은 회원 public_id와 is_new_user를 싣는다`() {
        analytics.googleLoginSucceeded(userPublicId = memberPublicId.toString(), isNewUser = true)

        val e = publisher.events.single()
        assertThat(e.eventType).isEqualTo("server_login_googleLogin_processed_succeeded")
        assertThat(e.userId).isEqualTo(memberPublicId.toString())
        assertThat(e.eventProperties["is_new_user"]).isEqualTo(true)
        assertThat(e.eventProperties["is_logged_in"]).isEqualTo(true)
    }

    @Test
    fun `로그인 실패는 게스트로 발행하고 error_type을 싣는다`() {
        analytics.googleLoginFailed(AnalyticsErrorType.VALIDATION)

        val e = publisher.events.single()
        assertThat(e.eventType).isEqualTo("server_login_googleLogin_processed_failed")
        assertThat(e.userId).isNull()
        assertThat(e.eventProperties["error_type"]).isEqualTo("validation")
    }

    @Test
    fun `마이그레이션 성공은 상태별 카운트를 집계해 싣는다`() {
        seedMember(9L)

        analytics.migrationSucceeded(
            userId = 9L,
            migratedStoryCount = 2,
            migratedChatCount = 1,
            alreadyOwnedCount = 3,
            conflictCount = 1,
            notFoundCount = 0,
        )

        val e = publisher.events.single()
        assertThat(e.eventType).isEqualTo("server_login_migration_processed_succeeded")
        assertThat(e.eventProperties["migrated_story_count"]).isEqualTo(2)
        assertThat(e.eventProperties["migrated_chat_count"]).isEqualTo(1)
        assertThat(e.eventProperties["already_owned_count"]).isEqualTo(3)
        assertThat(e.eventProperties["conflict_count"]).isEqualTo(1)
        assertThat(e.eventProperties["not_found_count"]).isEqualTo(0)
    }

    @Test
    fun `발행 준비 중 예외는 삼켜 호출부로 전파되지 않는다`() {
        val throwingPublisher = object : AnalyticsEventPublisher {
            override fun publish(event: AnalyticsEvent) = throw RuntimeException("boom")
        }
        val safe = ServerAnalytics(throwingPublisher, userRepository)

        // 예외가 던져지면 테스트가 실패한다(관측이 비즈니스를 깨지 않아야 함).
        safe.feedbackSubmissionSucceeded(userId = null)
    }
}
