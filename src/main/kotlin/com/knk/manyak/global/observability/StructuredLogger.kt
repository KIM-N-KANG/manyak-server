package com.knk.manyak.global.observability

import net.logstash.logback.argument.StructuredArguments
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 운영 분석용 구조화 이벤트 로깅 헬퍼(AN-3 §4).
 *
 * event_name과 임의 필드를 INFO 로그로 남긴다. 필드는 [StructuredArguments]로 전달되어
 * prod의 JSON 인코더에서는 최상위 JSON 필드로, 로컬 콘솔에서는 `key=value`로 보인다.
 * request_id/session_id/device_id_hash 같은 공통 식별자는 MDC에 있으면 인코더가 자동 부착하므로
 * 여기서 다시 넣지 않는다.
 *
 * 개인정보 원칙(§8): 채팅·피드백·이메일·프롬프트 원문은 절대 필드로 넣지 않는다.
 * 길이 구간(message_length_bucket)·content_length·has_email 같은 대체 값만 전달한다.
 */
@Component
class StructuredLogger {

    private val log = LoggerFactory.getLogger(StructuredLogger::class.java)

    fun event(eventName: String, fields: Map<String, Any?>) {
        val payload = LinkedHashMap<String, Any?>()
        payload["event_name"] = eventName
        payload.putAll(fields)
        log.info("{}", StructuredArguments.entries(payload))
    }

    fun event(eventName: String, vararg fields: Pair<String, Any?>) {
        event(eventName, fields.toMap())
    }
}
