package com.knk.manyak.push.inbox

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.outbox.ModerationOutboxListener
import com.knk.manyak.push.outbox.OutboxTransactionTestConfig
import com.knk.manyak.push.outbox.StoryCompletionOutboxListener
import com.knk.manyak.push.outbox.StoryTakedownOutboxListener
import com.knk.manyak.story.entity.StoryTakedownReason
import com.knk.manyak.story.event.StoryCompletedEvent
import com.knk.manyak.story.submission.StoryModerationCompleted
import com.knk.manyak.story.submission.StoryTakenDown
import com.knk.manyak.story.submission.SubmissionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.annotation.Order
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.Method
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Supplier
import kotlin.reflect.KClass

/**
 * 알림 내역 기록기가 완성, 검수 종료, 공개 중단 이벤트를 발행 트랜잭션 안에서 구독하는 방식을 고정한다(KNK-2067).
 *
 * - 순서: 기록기 리스너가 같은 이벤트의 원격 아웃박스 리스너보다 앞선다. 실행 순서 테스트는 등록 순서가 우연히 맞으면
 *   `@Order`가 빠져도 통과하므로, 순서 값 자체를 여기서 확인한다.
 * - 등록: 통합 테스트의 기록기는 `@MockitoSpyBean`이라 운영과 빈 생성 경로가 다르다. 스파이가 아닌 최소 컨텍스트에서도
 *   리스너로 등록되어 내역을 쓰는지 확인한다.
 */
class DomainNotificationRecorderListenerTests {

    private fun listenerFor(type: KClass<*>, event: KClass<*>): Method = type.java.declaredMethods.single {
        AnnotatedElementUtils.hasAnnotation(it, EventListener::class.java) && it.parameterTypes.contentEquals(arrayOf(event.java))
    }

    private fun orderOf(method: Method): Int =
        AnnotatedElementUtils.findMergedAnnotation(method, Order::class.java)?.value ?: Ordered.LOWEST_PRECEDENCE

    @ParameterizedTest(name = "{0}")
    @MethodSource("outboxListeners")
    fun `기록기 리스너는 MANDATORY이고 순서 값이 원격 아웃박스 리스너보다 앞선다`(event: KClass<*>, outbox: KClass<*>) {
        val recorder = listenerFor(DomainNotificationRecorder::class, event)
        val outboxListener = listenerFor(outbox, event)

        assertThat(AnnotatedElementUtils.findMergedAnnotation(recorder, Order::class.java)).isNotNull
        // 발행 시점에 동기로 도는 리스너여야 한다. @TransactionalEventListener도 @EventListener를 메타로 달아 위 탐색에 걸린다.
        assertThat(AnnotatedElementUtils.findMergedAnnotation(recorder, TransactionalEventListener::class.java)).isNull()
        assertThat(AnnotatedElementUtils.findMergedAnnotation(recorder, Transactional::class.java)!!.propagation)
            .isEqualTo(Propagation.MANDATORY)
        assertThat(orderOf(recorder)).isLessThan(orderOf(outboxListener))
    }

    @Test
    fun `스파이가 아닌 기록기 빈도 리스너로 등록되어 발행 트랜잭션 안에서만 내역을 쓴다`() {
        val dataSource = EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build()
        val store = mock(UserNotificationStore::class.java)
        val users = mock(UserRepository::class.java)
        `when`(users.findByIdForUpdate(USER_ID)).thenReturn(User(id = USER_ID, nickname = "작가"))
        val transactionManager = DataSourceTransactionManager(dataSource)
        try {
            AnnotationConfigApplicationContext().use { context ->
                context.register(OutboxTransactionTestConfig::class.java)
                context.registerBean("transactionManager", PlatformTransactionManager::class.java, Supplier { transactionManager })
                context.registerBean(UserNotificationStore::class.java, Supplier { store })
                context.registerBean(UserRepository::class.java, Supplier { users })
                context.registerBean(Clock::class.java, Supplier { Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC) })
                context.register(DomainNotificationRecorder::class.java)
                context.refresh()
                val recorder = context.getBean(DomainNotificationRecorder::class.java)
                assertThat(mockingDetails(AopTestUtils.getUltimateTargetObject<Any>(recorder)).isMock).isFalse()

                TransactionTemplate(transactionManager).executeWithoutResult {
                    context.publishEvent(StoryCompletedEvent(USER_ID, UUID.randomUUID(), UUID.randomUUID().toString(), "완성"))
                    context.publishEvent(StoryModerationCompleted(USER_ID, UUID.randomUUID().toString(), null, SubmissionStatus.REJECTED, 1, "검수"))
                    context.publishEvent(StoryTakenDown(USER_ID, UUID.randomUUID(), "공개 중단", StoryTakedownReason.HATE_SPEECH, null,
                        Instant.parse("2026-11-11T00:00:00Z")))
                }

                val rows = mockingDetails(store).invocations.filter { it.method.name == "insert" }.map { it.arguments[0] as UserNotification }
                assertThat(rows.map { it.type }).containsExactly(
                    NotificationType.STORY_COMPLETED, NotificationType.STORY_MODERATION_COMPLETED, NotificationType.STORY_TAKEN_DOWN,
                )
                assertThat(rows).allMatch { it.userId == USER_ID }
                assertThrows<IllegalTransactionStateException> {
                    context.publishEvent(StoryModerationCompleted(USER_ID, UUID.randomUUID().toString(), null, SubmissionStatus.FAILED, 1, "밖"))
                }
            }
        } finally {
            dataSource.shutdown()
        }
    }

    companion object {
        private const val USER_ID = 7L

        /** 같은 이벤트의 동기 구독자를 추가하면 이 목록에도 넣는다. */
        @JvmStatic
        fun outboxListeners(): List<Arguments> = listOf(
            Arguments.of(StoryCompletedEvent::class, StoryCompletionOutboxListener::class),
            Arguments.of(StoryModerationCompleted::class, ModerationOutboxListener::class),
            Arguments.of(StoryTakenDown::class, StoryTakedownOutboxListener::class),
        )
    }
}
