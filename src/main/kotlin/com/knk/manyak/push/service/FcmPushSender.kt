package com.knk.manyak.push.service

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.repository.DevicePushTokenRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * FCM 발송기(KNK-1130). 시나리오별 발송(스토리 완성 등)은 전부 이 클래스를 부른다.
 *
 * - **data 전용** 메시지다. `notification` 필드를 실으면 백그라운드에서 OS가 알아서 띄우고 앱 코드가 돌지 않아
 *   딥링크·문구를 앱이 제어할 수 없다. 알림 UI는 앱이 조립한다(KNK-1134).
 * - 우선순위 HIGH: data 전용 메시지는 기본(normal)이면 Doze에서 미뤄져 "완료됐다"는 알림이 늦게 뜬다.
 * - 커밋 뒤에 불러야 한다. 외부 IO라 도메인 트랜잭션 안에서 부르면 롤백돼도 푸시는 이미 나간다.
 * - 정지·탈퇴 계정에는 보내지 않는다. 등록 시점에는 ACTIVE였어도 그 뒤 상태가 바뀌면 남아 있던 토큰으로
 *   알림이 계속 나간다(스펙 §4-5 B20).
 * - 실패는 전부 삼키고 로그·메트릭만 남긴다. 푸시는 부가 기능이고, 진실의 원천은 복귀 조회(KNK-631)다.
 * - [messaging]이 null이면(서비스 계정 미설정, [com.knk.manyak.push.config.FcmConfig]) no-op이다.
 *
 * ponytail: 토큰마다 `send()` 한 번씩이다. 회원당 기기 수가 한 자리라 충분하고, 대량 발송(출석 리마인드)이
 * 수천 건을 넘기면 `sendEachForMulticast`(500개 묶음)로 바꾼다. 재시도도 두지 않는다 — 일시 오류(429·5xx)는
 * SDK가 내부에서 재시도하고, 그래도 실패한 한 건은 놓쳐도 복귀 조회가 덮는다.
 */
@Component
class FcmPushSender(
    // Kotlin nullable 생성자 인자 = 선택 주입. FcmConfig가 null을 돌려주면 여기도 null이다.
    private val messaging: FirebaseMessaging?,
    private val devicePushTokenRepository: DevicePushTokenRepository,
    private val userRepository: UserRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 회원의 등록 기기 전부에 [data]를 보낸다. 발송기가 비활성이거나 토큰이 없으면 아무 일도 하지 않는다. */
    fun sendToUser(userId: Long, data: Map<String, String>) {
        val messaging = this.messaging
        if (messaging == null) {
            log.debug("FCM 발송기가 비활성이라 푸시를 건너뜁니다. (userId={})", userId)
            return
        }
        // 정지·탈퇴 회원의 남은 토큰으로는 보내지 않는다(Codex 3차 리뷰 P2). isActiveAccessAllowed는 DELETED를
        // 통과시키므로 여기서는 쓰지 않는다 — 탈퇴 정리와 발송이 엇갈리는 창에서도 막아야 한다.
        val status = userRepository.findById(userId).orElse(null)?.status
        if (status != UserStatus.ACTIVE) {
            log.debug("활성 회원이 아니라 푸시를 건너뜁니다. (userId={}, status={})", userId, status)
            return
        }
        devicePushTokenRepository.findTop10ByUserIdOrderByUpdatedAtDesc(userId).forEach { deviceToken ->
            sendTo(messaging, deviceToken, data)
        }
    }

    private fun sendTo(messaging: FirebaseMessaging, deviceToken: DevicePushToken, data: Map<String, String>) {
        val message = Message.builder()
            .setToken(deviceToken.token)
            .putAllData(data)
            .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
            .build()
        try {
            messaging.send(message)
            count(OUTCOME_SUCCESS)
        } catch (ex: FirebaseMessagingException) {
            // UNREGISTERED만 지운다. INVALID_ARGUMENT는 토큰 형식 오류뿐 아니라 **우리 페이로드 오류**에도 오므로,
            // 그걸 삭제 신호로 쓰면 서버 버그 하나가 회원 전체의 토큰을 지운다. 형식이 깨진 토큰은 앱이 FCM SDK에서
            // 받은 값을 그대로 올리는 경로라 실제로 드물고, 남더라도 발송 실패 메트릭으로 드러난다.
            if (ex.messagingErrorCode == MessagingErrorCode.UNREGISTERED) {
                // 정리 실패를 따로 가둔다. DataAccessException은 형제 catch(RuntimeException)에 잡히지 않고
                // sendToUser 밖으로 새어나가 뒤 기기 발송을 통째로 끊는다(Codex 3차 리뷰 P2).
                try {
                    devicePushTokenRepository.deleteById(deviceToken.id)
                    count(OUTCOME_UNREGISTERED)
                    log.info("무효 FCM 토큰을 정리했습니다. (userId={}, token={})", deviceToken.userId, mask(deviceToken.token))
                } catch (cleanupEx: RuntimeException) {
                    count(OUTCOME_FAILURE)
                    log.warn(
                        "무효 FCM 토큰 정리에 실패했습니다. (userId={}, token={}, error={})",
                        deviceToken.userId, mask(deviceToken.token), cleanupEx.javaClass.simpleName,
                    )
                }
            } else {
                count(OUTCOME_FAILURE)
                log.warn(
                    "FCM 발송에 실패했습니다. (userId={}, token={}, code={}, error={})",
                    deviceToken.userId, mask(deviceToken.token), ex.messagingErrorCode, ex.javaClass.simpleName,
                )
            }
        } catch (ex: RuntimeException) {
            // SDK 내부 오류·잘못된 메시지 조립 등. 한 기기 실패가 다른 기기 발송을 막지 않는다.
            count(OUTCOME_FAILURE)
            log.warn(
                "FCM 발송 중 예외가 났습니다. (userId={}, token={}, error={})",
                deviceToken.userId, mask(deviceToken.token), ex.javaClass.simpleName,
            )
        }
    }

    private fun count(outcome: String) {
        Counter.builder(METRIC_PUSH_SEND_RESULT).tag("outcome", outcome).register(meterRegistry).increment()
    }

    // 토큰은 그 기기로 푸시를 보낼 수 있는 주소라 로그에 전체를 남기지 않는다.
    private fun mask(token: String): String = token.take(TOKEN_LOG_PREFIX) + "…"

    companion object {
        const val METRIC_PUSH_SEND_RESULT = "manyak.push.send.result"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_UNREGISTERED = "unregistered"
        const val OUTCOME_FAILURE = "failure"
        val OUTCOMES = listOf(OUTCOME_SUCCESS, OUTCOME_UNREGISTERED, OUTCOME_FAILURE)
        private const val TOKEN_LOG_PREFIX = 12
    }
}
