package com.knk.manyak.global.observability.aicall

import com.knk.manyak.global.observability.MdcKeys
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * AI 호출을 감싸 ai_call_logs에 적재한다.
 *
 * 호출 직전 STARTED로 적재하고, [block] 실행 결과에 따라 SUCCEEDED/FAILED로 전이한다.
 * latency는 여기서 측정하고, request_id·session_id·anonymous_id_hash는 MDC에서 읽는다.
 * (chat_response처럼 chatSseExecutor 워커에서 호출돼도 MdcTaskDecorator가 MDC를 전파하므로 동일하게 동작한다.)
 *
 * 반환한 [RecordedAiCall.aiCallLogId]를 호출부가 ai_response_saved 등 로그에 실어 상관관계를 잇는다.
 */
@Component
class AiCallRecorder(
    private val repository: AiCallLogRepository,
    @param:Value("\${spring.application.name:manyak-server}")
    private val callerService: String,
) {
    /**
     * [block]을 실행하며 호출 이력을 적재한다. 성공 시 결과와 적재 id를 반환하고,
     * 실패 시 FAILED로 적재한 뒤 원래 예외를 그대로 전파한다.
     *
     * @param errorCode 실패 예외를 error_code로 매핑한다. 기본은 예외 클래스 이름.
     *  도메인 오류 코드를 가진 호출부(예: ChatTurnAiException.code)는 직접 넘긴다.
     */
    fun <T> record(
        context: AiCallContext,
        errorCode: (Throwable) -> String? = { it::class.simpleName },
        block: () -> T,
    ): RecordedAiCall<T> {
        val log = repository.save(started(context))
        val startNanos = System.nanoTime()
        try {
            val result = block()
            log.markSucceeded(latencyMs = elapsedMs(startNanos))
            repository.save(log)
            return RecordedAiCall(result, log.id)
        } catch (throwable: Throwable) {
            log.markFailed(latencyMs = elapsedMs(startNanos), errorCode = errorCode(throwable))
            repository.save(log)
            throw throwable
        }
    }

    private fun started(context: AiCallContext) = AiCallLog(
        // request_id는 NOT NULL이므로 MDC에 없으면 최소 "unknown"으로 채운다.
        // 클라이언트가 헤더로 보낸 값이 컬럼 길이를 넘어도 적재(=관측)가 비즈니스 호출을 깨지 않도록 자른다.
        requestId = (MDC.get(MdcKeys.REQUEST_ID) ?: UNKNOWN).take(IDENTIFIER_MAX_LENGTH),
        callerService = callerService,
        feature = context.feature,
        // session_id·anonymous_id_hash는 헤더 누락 시 필터가 "unknown"으로 채우므로, 분석을 위해 null로 정규화한다.
        anonymousIdHash = cleanMdc(MdcKeys.ANONYMOUS_ID_HASH),
        sessionId = cleanMdc(MdcKeys.SESSION_ID),
        storyId = context.storyId,
        chatId = context.chatId,
        turnIndex = context.turnIndex,
    )

    private fun cleanMdc(key: String): String? =
        MDC.get(key)?.takeIf { it.isNotBlank() && it != UNKNOWN }?.take(IDENTIFIER_MAX_LENGTH)

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    companion object {
        private const val UNKNOWN = "unknown"

        // request_id·session_id 컬럼 길이(VARCHAR(128))와 일치. 초과 입력은 잘라 적재 실패를 막는다.
        private const val IDENTIFIER_MAX_LENGTH = 128
    }
}

/**
 * 호출부가 아는, MDC에 없는 식별자. feature는 필수이고 나머지는 호출 종류에 따라 채운다.
 * (storyline_generation은 story_id 없음, chat_response만 chat_id·turn_index가 있음)
 */
data class AiCallContext(
    val feature: AiCallFeature,
    val storyId: Long? = null,
    val chatId: UUID? = null,
    val turnIndex: Int? = null,
)

/** 적재된 호출의 결과와 ai_call_log_id(상관관계 연결용). */
data class RecordedAiCall<T>(
    val result: T,
    val aiCallLogId: Long,
)
