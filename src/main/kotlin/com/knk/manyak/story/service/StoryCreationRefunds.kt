package com.knk.manyak.story.service

import com.knk.manyak.global.observability.StructuredLogger
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * 간편 제작 스토리라인과 완성의 환불, 횟수 복원 결과를 기록한다(STORY 5-3-4, 5-4-5, 5-4-7, KNK-2081).
 *
 * 되돌리기가 실패해도 원래 오류로 응답해야 하므로 [restore]는 예외를 던지지 않는다. 실패는 `manyak.story.creation.refund`의
 * `failure`와 `story_creation_refund_failed` 로그로 남기고, 보상 예외는 원래 예외에 덧붙인다(5xx면 Sentry 이벤트에 함께 남는다).
 * 로그에는 회원 내부 id와 예외 메시지를 싣지 않는다(예외 클래스 이름만).
 *
 * 두 시계열은 기동 시 0으로 등록한다. 첫 증가 때 생기면 `increase()`가 첫 실패를 놓친다([com.knk.manyak.chat.service.ChatTurnRefundMeters]와 같은 이유).
 */
@Component
class StoryCreationRefunds(
    private val meterRegistry: MeterRegistry,
    private val structuredLogger: StructuredLogger,
) : MeterBinder {

    enum class Stage(val value: String) { STORYLINE_GENERATION("storyline_generation"), STORY_COMPLETION("story_completion") }

    enum class Requester(val value: String) { GUEST("guest"), MEMBER_TRIAL("member_trial"), MEMBER_CREDIT("member_credit") }

    override fun bindTo(registry: MeterRegistry) {
        listOf(OUTCOME_SUCCESS, OUTCOME_FAILURE).forEach { counter(registry, it) }
    }

    /**
     * 되돌릴 대상이 있을 때만 부른다. [compensate]가 false를 돌려주거나(회원 체험 복원의 Redis 실패) 예외를 던지면 실패로 기록하고,
     * 예외는 [original]에 덧붙인다. [creationId]는 진행 ID(발급 전이면 null), [idempotencyKey]는 회원 이프 환불만.
     */
    fun restore(
        original: Throwable,
        stage: Stage,
        requester: Requester,
        creationId: Long?,
        idempotencyKey: String? = null,
        compensate: () -> Boolean,
    ) {
        val error = try {
            if (compensate()) null else MEMBER_TRIAL_RESTORE_FAILED
        } catch (exception: Exception) {
            original.addSuppressed(exception)
            exception::class.simpleName ?: "UNKNOWN"
        }
        // 관측 실패가 원래 예외 전파를 막지 않게 격리한다.
        runCatching { counter(meterRegistry, if (error == null) OUTCOME_SUCCESS else OUTCOME_FAILURE).increment() }
        if (error == null) return
        runCatching {
            structuredLogger.event(
                EVENT_REFUND_FAILED,
                buildMap {
                    put("stage", stage.value)
                    put("requester", requester.value)
                    put("creation_id", creationId)
                    idempotencyKey?.let { put("idempotency_key", it) }
                    put("error", error)
                },
            )
        }
    }

    private fun counter(registry: MeterRegistry, outcome: String): Counter =
        Counter.builder(METRIC).description("간편 제작 환불과 횟수 복원 결과").tag("outcome", outcome).register(registry)

    private companion object {
        const val METRIC = "manyak.story.creation.refund"
        const val EVENT_REFUND_FAILED = "story_creation_refund_failed"
        const val MEMBER_TRIAL_RESTORE_FAILED = "member_trial_restore_failed"
    }
}
