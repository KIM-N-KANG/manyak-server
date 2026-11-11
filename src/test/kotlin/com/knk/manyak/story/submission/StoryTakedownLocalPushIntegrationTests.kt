package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.MdcKeys
import com.knk.manyak.push.inbox.DomainNotificationRecorder
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryTakedownReason
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.MockedBoundaryIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executor

/**
 * 공개 중단의 local 발송(NOTIFICATION 4-1-21). 발송은 커밋 뒤 푸시 실행기에서만 일어나고 서비스 알림 설정을 따른다.
 * 리스너가 다른 패키지로 옮겨져도 빈 등록과 메시지 ID가 그대로인지 고정한다(KNK-2067).
 */
class StoryTakedownLocalPushIntegrationTests : MockedBoundaryIntegrationTest() {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var takedowns: StoryTakedownService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var sender: FcmPushSender
    @Autowired @Qualifier("pushExecutor") lateinit var pushExecutor: Executor
    @Autowired lateinit var recorder: DomainNotificationRecorder
    private lateinit var admin: User

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        MDC.put(MdcKeys.REQUEST_ID, "takedown-local-push")
        // 커밋 뒤 제출을 테스트 스레드에서 끝내 검증을 결정적으로 만든다.
        doAnswer { it.getArgument<Runnable>(0).run(); null }.`when`(pushExecutor).execute(any(Runnable::class.java))
    }

    @AfterEach fun clearMdc() = MDC.clear()

    private fun story(servicePushEnabled: Boolean): Story {
        val owner = users.save(User(nickname = "작가", servicePushEnabled = servicePushEnabled))
        return stories.save(Story(userId = owner.id, title = "{username}의 약속", protagonistName = "서린"))
    }

    private fun takeDown(story: Story, rollback: Boolean = false): StoryTakedownResponse? =
        TransactionTemplate(transactionManager).execute { status ->
            takedowns.takeDown(admin.id, story.publicId, StoryTakedownRequest(StoryTakedownReason.HATE_SPEECH)).also {
                verifyNoInteractions(sender)
                if (rollback) status.setRollbackOnly()
            }
        }

    @Test fun `서비스 알림을 켠 소유자에게 커밋 뒤에만 메시지 ID와 함께 보낸다`() {
        val story = story(servicePushEnabled = true)

        val response = takeDown(story)!!

        // 발송은 요청 스레드가 아니라 푸시 실행기에 제출된다.
        verify(pushExecutor).execute(any(Runnable::class.java))
        val call = mockingDetails(sender).invocations.single()
        assertThat(call.method.name).isEqualTo("sendToUser")
        assertThat(call.arguments[0]).isEqualTo(story.userId)
        val data = call.arguments[1] as Map<*, *>
        assertThat(data["type"]).isEqualTo("STORY_TAKEN_DOWN")
        assertThat(data["storyId"]).isEqualTo(story.publicId.toString())
        assertThat(data["body"] as String).startsWith("「서린의 약속」은 혐오 표현 사유로")
        assertThat(call.arguments.last()).isEqualTo("story-taken-down:${story.publicId}:${response.takenDownAt.toEpochMilli()}")
        // 내역은 충돌 무시 삽입이라 행 수로는 이중 기록을 못 잡는다. 기록기는 이벤트 구독으로 한 번만 불리고 내역은 1행이다.
        val recorderSpy = AopTestUtils.getUltimateTargetObject<DomainNotificationRecorder>(recorder)
        assertThat(mockingDetails(recorderSpy).invocations.count { it.method.name == "takenDown" }).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE type = 'STORY_TAKEN_DOWN'", Long::class.java)).isEqualTo(1)
    }

    @Test fun `서비스 알림을 끈 소유자는 같은 메시지 ID로 폐기를 기록하고 보내지 않는다`() {
        val story = story(servicePushEnabled = false)

        val response = takeDown(story)!!

        val call = mockingDetails(sender).invocations.single()
        assertThat(call.method.name).isEqualTo("recordDiscarded")
        assertThat(call.arguments[0]).isEqualTo(story.userId)
        assertThat(call.arguments[2]).isEqualTo("story-taken-down:${story.publicId}:${response.takenDownAt.toEpochMilli()}")
        assertThat(call.arguments[3]).isEqualTo("SERVICE_PUSH_DISABLED")
    }

    @Test fun `공개 중단이 롤백되면 보내지 않는다`() {
        val story = story(servicePushEnabled = true)

        takeDown(story, rollback = true)

        verifyNoInteractions(sender)
        assertThat(stories.findById(story.id).orElseThrow().takenDownAt).isNull()
    }
}
