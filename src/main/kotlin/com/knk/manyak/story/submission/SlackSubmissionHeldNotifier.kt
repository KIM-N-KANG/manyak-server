package com.knk.manyak.story.submission

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.UUID

data class SubmissionHeld(val submissionId: UUID, val kind: SubmissionKind, val reason: String, val attempts: Int)

/** 보류 전환 커밋 뒤 한 번 발송한다. 호출자는 이미 전용 검수 실행기이며 기본 @Async를 사용하지 않는다. */
@Component
class SlackSubmissionHeldNotifier(@Value("\${manyak.slack.moderation-webhook-url:}") webhookUrl: String) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val webhookUrl = webhookUrl.trim()
    private val client = RestClient.builder().requestFactory(SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(Duration.ofSeconds(2))
        setReadTimeout(Duration.ofSeconds(3))
    }).build()

    @TransactionalEventListener
    fun onHeld(event: SubmissionHeld) {
        if (webhookUrl.isEmpty()) {
            log.warn("moderation_held submission={} kind={} reason={} attempts={}", event.submissionId, event.kind, event.reason, event.attempts)
            return
        }
        try {
            client.post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("text" to "submissionId=${event.submissionId}\nkind=${event.kind}\nreason=${event.reason}\nattempts=${event.attempts}"))
                .retrieve().toBodilessEntity()
        } catch (ex: RuntimeException) {
            // 웹훅 secret·응답·사용자 원문이 예외 메시지에 포함될 수 있다.
            log.warn("moderation_hold_notification_failed submission={} error={}", event.submissionId, ex.javaClass.simpleName)
        }
    }
}
