package com.knk.manyak.push

import com.google.firebase.messaging.AndroidConfig.Priority.HIGH
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.event.StoryCompletionPushListener
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.story.event.StoryCompletedEvent
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskRejectedException
import java.util.concurrent.Executor
import java.util.Optional
import java.util.UUID

/**
 * 발송 실행 위치 전환(KNK-1375).
 *
 * `manyak.push.mode`가 `remote`면 스토리 완성 푸시를 서버 안의 발송기가 아니라 알림 서비스가 실행한다.
 * remote의 기록은 동기 아웃박스 리스너가 맡고 수신 동의는 소비자가 발송 직전에 확인한다.
 *
 * Spring 컨텍스트를 새로 띄우지 않는다 — 분기 하나를 고정하는 테스트라 리스너를 직접 만들어 검증한다
 * (모드가 다른 `@SpringBootTest`를 추가하면 컨텍스트가 하나 더 생긴다 — SpringContextBudgetGuardTests).
 *
 * 매처를 쓰지 않고 구체값으로 검증한다. Mockito 매처는 값이 아니라 타입 기본값(참조형은 null)을 돌려주므로,
 * Kotlin의 non-null 파라미터에 넘기면 호출 전에 `eq(...) must not be null`로 깨진다.
 */
class StoryCompletionPushModeTests {

    private val userRepository = mock(UserRepository::class.java)
    private val fcmPushSender = mock(FcmPushSender::class.java)

    private val storyPublicId = UUID.randomUUID().toString()
    private val event = StoryCompletedEvent(userId = USER_ID, requestId = UUID.randomUUID(), storyPublicId = storyPublicId, title = "제목")

    private val expectedData = mapOf(
        "type" to STORY_COMPLETED,
        "storyId" to storyPublicId,
        "title" to "제목",
        "deepLink" to "$WEB_BASE_URL/stories/$storyPublicId",
    )

    // 제출을 즉시 실행해 검증을 결정적으로 만든다. 실행기를 실제 풀로 두면 verify가 경쟁한다.
    private fun listener(mode: String, executor: Executor = SyncTaskExecutor()) = StoryCompletionPushListener(
        userRepository = userRepository,
        fcmPushSender = fcmPushSender,
        pushExecutor = executor,
        pushMode = mode,
        webBaseUrl = WEB_BASE_URL,
    )

    private fun givenUser(servicePushEnabled: Boolean = true): User {
        val user = User(id = USER_ID, nickname = "제작자#0001").apply {
            this.servicePushEnabled = servicePushEnabled
        }
        `when`(userRepository.findById(USER_ID)).thenReturn(Optional.of(user))
        return user
    }

    @Test
    fun `local 모드는 서버 안의 발송기를 부른다`() {
        givenUser()

        listener("local").onStoryCompleted(event)

        verify(fcmPushSender).sendToUser(USER_ID, expectedData, HIGH, null)
    }

    @Test
    fun `remote 모드는 HTTP와 FCM 발송 없이 반환한다`() {
        listener("remote").onStoryCompleted(event)
        verifyNoInteractions(userRepository, fcmPushSender)
    }

    @Test
    fun `실행기가 제출을 거부해도 예외가 전파되지 않는다`() {
        givenUser()
        val saturated = Executor { throw TaskRejectedException("큐가 가득 찼습니다") }

        // 거부는 @Async 프록시를 쓰면 메서드 본문 밖에서 나고, 그러면 이미 커밋된 스토리 생성이 500이 된다.
        assertThatCode { listener("local", saturated).onStoryCompleted(event) }.doesNotThrowAnyException()
        verifyNoInteractions(fcmPushSender)
    }

    private companion object {
        const val USER_ID = 7L
        const val WEB_BASE_URL = "https://manyak.app"
        const val STORY_COMPLETED = "STORY_COMPLETED"
    }
}
