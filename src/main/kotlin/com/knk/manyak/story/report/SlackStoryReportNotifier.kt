package com.knk.manyak.story.report

import io.sentry.Sentry
import io.sentry.SentryLevel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Slack Incoming Webhook 으로 신고 접수를 알린다(KNK-1020). [SlackFeedbackNotifier]와 같은 패턴이되,
 * 신고는 조치가 필요한 운영 이벤트라 **피드백과 채널(웹훅)을 분리**한다.
 *
 * - webhook URL 이 비어 있으면(미설정) 발송을 건너뛴다.
 * - 발송 실패는 흡수하고 로깅·Sentry 만 남긴다(신고 접수 자체는 이미 저장돼 있다).
 * - 신고 저장은 트랜잭션 없이 이뤄지므로(멱등 유니크 흡수 — 좋아요 선례) @TransactionalEventListener 가 아니라
 *   평문 @EventListener + @Async 로 요청 스레드와 분리한다.
 */
@Component
class SlackStoryReportNotifier(
    @Value("\${manyak.slack.report-webhook-url:}") webhookUrl: String,
    connectTimeout: Duration = Duration.ofSeconds(2),
    readTimeout: Duration = Duration.ofSeconds(3),
) {
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

    @Async
    @EventListener
    fun onStoryReported(event: StoryReportedEvent) {
        if (webhookUrl.isEmpty()) {
            log.debug("Slack webhook URL 이 설정되지 않아 신고 알림을 건너뜁니다. (reportId={})", event.reportId)
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
            // 예외 메시지/스택에는 webhook URL(secret 토큰 포함)이 섞일 수 있어, 예외 타입만 남긴다(피드백 선례).
            log.warn("신고 Slack 알림 발송에 실패했습니다. (reportId={}, error={})", event.reportId, ex.javaClass.simpleName)
            Sentry.captureMessage(
                "신고 Slack 알림 발송 실패: reportId=${event.reportId}, error=${ex.javaClass.simpleName}",
                SentryLevel.WARNING,
            )
        }
    }

    private fun buildMessage(event: StoryReportedEvent): String =
        buildString {
            appendLine("🚨 스토리 신고 접수")
            // 스토리 제목·상세는 사용자 입력이다. mrkdwn 멘션(<!channel>)·위장 링크로 파싱되지 않게 이스케이프한다(피드백 선례).
            appendLine("- 스토리: ${escapeSlack(event.storyTitle)} (${event.storyPublicId})")
            appendLine("- 사유: ${event.reason}")
            event.detail?.takeIf { it.isNotBlank() }?.let { appendLine("- 상세: ${escapeSlack(it)}") }
            append("- 접수 시각: ${event.createdAt}")
        }

    // Slack mrkdwn 제어문자 이스케이프. 순서 중요: & 를 먼저 치환해 이중 이스케이프를 막는다(SlackFeedbackNotifier와 동일).
    private fun escapeSlack(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
