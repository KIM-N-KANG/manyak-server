package com.knk.manyak.push.client

import com.knk.manyak.global.observability.CorrelationHeaders
import com.knk.manyak.push.dto.PushKind
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.UUID

/**
 * 알림 서비스(manyak-notification)에 발송을 요청한다(KNK-1375).
 *
 * 이 클라이언트가 넘기는 것은 **무엇을 누구에게 보낼지**뿐이다. 기기 토큰과 수신 동의는 알림 서비스가
 * 발송 직전에 서버 내부 API로 다시 조회하므로 여기에 싣지 않는다. 동의를 스냅샷으로 넘기면 철회와 발송
 * 사이에 창이 생기고, 그 창에서 수신을 끈 회원에게 광고가 나간다.
 *
 * - `base-url`이 비어 있으면 발송을 건너뛴다(FCM 서비스 계정·Slack 웹훅과 같은 관례). remote 모드로
 *   전환해도 주소를 주입하기 전까지는 조용히 no-op이라, 설정 순서를 틀려도 요청이 깨지지 않는다.
 * - 공유 시크릿은 서버 내부 API와 같은 값을 쓴다. 양방향이 같은 비밀 하나를 본다.
 * - 상관 식별자는 MDC에서 꺼내 그대로 전달해, 서버와 알림 서비스 로그가 같은 `request_id`로 묶인다.
 * - 호출은 `@Async` 스레드에서 일어나지만 응답을 무한정 기다리면 그 스레드가 묶인다. 연결과 읽기에
 *   타임아웃을 둔다.
 */
@Component
class NotificationClient(
    @Value("\${manyak.push.notification.base-url:}") baseUrl: String,
    @Value("\${manyak.internal.shared-secret:}") private val sharedSecret: String,
    @Value("\${manyak.push.notification.connect-timeout:5s}") connectTimeout: Duration = Duration.ofSeconds(5),
    @Value("\${manyak.push.notification.read-timeout:10s}") readTimeout: Duration = Duration.ofSeconds(10),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val baseUrl = baseUrl.trim().trimEnd('/')
    private val restClient = RestClient
        .builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(connectTimeout)
                setReadTimeout(readTimeout)
            },
        )
        .build()

    /**
     * @param recipientId 수신 회원의 `public_id`. 내부 PK는 서비스 경계를 넘기지 않는다(IDOR 방지 관례).
     * @param data 시나리오 페이로드. 앱이 알림 UI를 조립하는 데 쓰는 `type`을 포함한다.
     */
    fun send(recipientId: UUID, kind: PushKind, type: String, data: Map<String, String>) {
        if (baseUrl.isEmpty()) {
            log.debug("알림 서비스 주소가 설정되지 않아 발송 요청을 건너뜁니다. (recipientId={}, type={})", recipientId, type)
            return
        }
        val response = restClient
            .post()
            .uri("$baseUrl/internal/notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HEADER_INTERNAL_SECRET, sharedSecret)
            .apply { CorrelationHeaders.forwardingHeadersFromMdc().forEach { (name, value) -> header(name, value) } }
            .body(
                mapOf(
                    "recipientId" to recipientId.toString(),
                    "kind" to kind.name,
                    "type" to type,
                    "data" to data,
                ),
            )
            .retrieve()
            .body(Map::class.java)
        // 건너뜀(수신 거부·토큰 없음)과 실패를 구분해 남긴다. 둘 다 예외가 아니라 정상 응답으로 온다.
        log.info(
            "알림 서비스에 발송을 요청했습니다. (recipientId={}, type={}, outcome={}, reason={})",
            recipientId, type, response?.get("outcome"), response?.get("reason"),
        )
    }

    private companion object {
        const val HEADER_INTERNAL_SECRET = "X-Manyak-Internal-Secret"
    }
}
