package com.knk.manyak.feedback.notification

import com.knk.manyak.feedback.event.FeedbackCreatedEvent
import io.sentry.Sentry
import io.sentry.SentryLevel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * 신규 피드백을 구글 폼 `formResponse` 엔드포인트로 POST 해, 폼에 연결된
 * 스프레드시트(엑셀)에 적재한다. Slack 알림과 병행하는 부가 채널이다.
 *
 * - form-id(또는 본문 entry-id)가 비어 있으면(미설정) 발송을 건너뛴다.
 * - 발송 중 발생하는 예외는 모두 흡수하고 로깅만 한다(부가 기능 실패 격리).
 */
@Component
class GoogleFormFeedbackNotifier(
    @Value("\${manyak.google-form.base-url:https://docs.google.com}") baseUrl: String,
    @Value("\${manyak.google-form.feedback.form-id:}") formId: String,
    @Value("\${manyak.google-form.feedback.body-entry-id:}") bodyEntryId: String,
    @Value("\${manyak.google-form.feedback.email-entry-id:}") emailEntryId: String,
    @Value("\${manyak.google-form.feedback.platform-entry-id:}") platformEntryId: String,
    @Value("\${manyak.google-form.feedback.app-version-entry-id:}") appVersionEntryId: String,
    connectTimeout: Duration = Duration.ofSeconds(2),
    readTimeout: Duration = Duration.ofSeconds(3),
) : FeedbackNotifier {
    private val log = LoggerFactory.getLogger(javaClass)
    private val formId = formId.trim()
    private val bodyEntryId = bodyEntryId.trim()
    private val emailEntryId = emailEntryId.trim()
    private val platformEntryId = platformEntryId.trim()
    private val appVersionEntryId = appVersionEntryId.trim()
    private val responseUrl = "${baseUrl.trim().removeSuffix("/")}/forms/d/e/${this.formId}/formResponse"
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
        if (formId.isEmpty() || bodyEntryId.isEmpty()) {
            log.debug("구글 폼 설정이 없어 피드백 적재를 건너뜁니다. (feedbackId={})", event.id)
            return
        }
        val form = LinkedMultiValueMap<String, String>().apply {
            add("entry.$bodyEntryId", event.body)
            // 이메일·플랫폼·앱 버전은 폼에서 선택 항목이라, entry-id 와 값이 모두 있을 때만 채운다.
            if (emailEntryId.isNotEmpty() && !event.email.isNullOrBlank()) {
                add("entry.$emailEntryId", event.email)
            }
            if (platformEntryId.isNotEmpty() && event.platform != null) {
                add("entry.$platformEntryId", event.platform.name)
            }
            if (appVersionEntryId.isNotEmpty() && !event.appVersion.isNullOrBlank()) {
                add("entry.$appVersionEntryId", event.appVersion)
            }
        }
        try {
            restClient
                .post()
                .uri(responseUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity()
        } catch (ex: RuntimeException) {
            // 적재는 부가 기능이므로 실패해도 피드백 등록 흐름에 영향을 주지 않는다.
            log.warn(
                "피드백 구글 폼 적재에 실패했습니다. (feedbackId={}, error={})",
                event.id,
                ex.javaClass.simpleName,
            )
            // 삼켜지는 실패라 자동 감지가 안 되므로 Sentry로도 보낸다.
            Sentry.captureMessage("Google Form feedback submit failed: ${ex.javaClass.simpleName}", SentryLevel.WARNING)
        }
    }
}
