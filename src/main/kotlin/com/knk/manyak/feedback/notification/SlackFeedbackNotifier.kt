package com.knk.manyak.feedback.notification

import com.knk.manyak.feedback.event.FeedbackCreatedEvent
import io.sentry.Sentry
import io.sentry.SentryLevel
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
            // 예외 메시지/스택에는 webhook URL(secret 토큰 포함)이 섞일 수 있어, 예외 타입만 남긴다.
            log.warn(
                "피드백 Slack 알림 발송에 실패했습니다. (feedbackId={}, error={})",
                event.id,
                ex.javaClass.simpleName,
            )
            // 삼켜지는 실패라 자동 감지가 안 되므로 Sentry로도 보낸다.
            // 예외 객체(메시지·스택에 webhook URL secret 포함 가능)는 보내지 않고 타입만 메시지로 남긴다.
            Sentry.captureMessage("Slack feedback webhook failed: ${ex.javaClass.simpleName}", SentryLevel.WARNING)
        }
    }

    private fun buildMessage(event: FeedbackCreatedEvent): String {
        val meta = buildList {
            // platform 은 enum 이라 안전하지만, 사용자 입력 메타는 이스케이프한다.
            event.platform?.let { add("platform: $it") }
            event.appVersion?.let { add("appVersion: ${escapeSlack(it)}") }
            event.email?.let { add("email: ${escapeSlack(it)}") }
        }.joinToString(" · ")

        // public API 입력이 Slack mrkdwn(멘션/링크)으로 파싱되지 않도록 본문을 이스케이프한다.
        // 줄바꿈은 이스케이프 이후에 인용 블록으로 이어지도록 처리한다.
        val body = escapeSlack(event.body).replace("\n", "\n> ")

        return buildString {
            append(":speech_balloon: *새 피드백 #${event.id}*\n")
            append("> ").append(body)
            if (meta.isNotEmpty()) {
                append("\n").append(meta)
            }
            append("\n_").append(event.createdAt).append("_")
        }
    }

    // Slack mrkdwn 제어문자 이스케이프. 순서 중요: & 를 먼저 치환해 이중 이스케이프를 막는다.
    private fun escapeSlack(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
