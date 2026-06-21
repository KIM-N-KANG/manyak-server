package com.knk.manyak.feedback.notification

import com.knk.manyak.feedback.event.FeedbackCreatedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Slack Incoming Webhook 으로 신규 피드백을 알린다.
 *
 * - webhook URL 이 비어 있으면(미설정) 발송을 건너뛴다.
 * - 발송 중 발생하는 예외는 모두 흡수하고 로깅만 한다(부가 기능 실패 격리).
 */
@Component
class SlackFeedbackNotifier(
    @Value("\${manyak.slack.feedback-webhook-url:}") webhookUrl: String,
    connectTimeout: Duration = Duration.ofSeconds(2),
    readTimeout: Duration = Duration.ofSeconds(3),
) : FeedbackNotifier {
    private val log = LoggerFactory.getLogger(javaClass)
    private val webhookUrl = webhookUrl.trim()
    private val restClient = RestClient
        .builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(connectTimeout)
                setReadTimeout(readTimeout)
            },
        )
        .build()

    override fun notifyCreated(event: FeedbackCreatedEvent) {
        if (webhookUrl.isEmpty()) {
            log.debug("Slack webhook URL 이 설정되지 않아 피드백 알림을 건너뜁니다. (feedbackId={})", event.id)
            return
        }
        try {
            restClient
                .post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("text" to buildMessage(event)))
                .retrieve()
                .toBodilessEntity()
        } catch (ex: RuntimeException) {
            // 알림은 부가 기능이므로 실패해도 피드백 등록 흐름에 영향을 주지 않는다.
            log.warn("피드백 Slack 알림 발송에 실패했습니다. (feedbackId={})", event.id, ex)
        }
    }

    private fun buildMessage(event: FeedbackCreatedEvent): String {
        val meta = buildList {
            event.platform?.let { add("platform: $it") }
            event.appVersion?.let { add("appVersion: $it") }
            event.email?.let { add("email: $it") }
        }.joinToString(" · ")

        return buildString {
            append(":speech_balloon: *새 피드백 #${event.id}*\n")
            // 본문은 인용 블록으로 표시하고, 줄바꿈도 인용이 이어지도록 한다.
            append("> ${event.body.replace("\n", "\n> ")}")
            if (meta.isNotEmpty()) {
                append("\n").append(meta)
            }
            append("\n_").append(event.createdAt).append("_")
        }
    }
}
