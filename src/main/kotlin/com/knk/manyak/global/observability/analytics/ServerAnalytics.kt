package com.knk.manyak.global.observability.analytics

import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.MdcKeys
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 서버 분석 이벤트(스펙 §6-4 `server_*`)를 의미 단위로 발행하는 도메인 파사드(B1, KNK-514).
 *
 * 각 호출부(스토리라인 생성·채팅 응답·피드백·로그인·마이그레이션)는 이 파사드의 타입 안전한 메서드를 호출하고,
 * 이벤트명·프로퍼티·식별자 해석·[AnalyticsErrorType] 매핑은 모두 여기서 한 곳에 모은다.
 *
 * 식별자(§6-2): 회원 요청은 `user_id`=public_id, 기기 식별자는 `device_id`=요청 헤더 원본(Amplitude 전용 문맥). 공통 프로퍼티로
 * `is_logged_in`·`request_id`·`device_id_hash`(실제 해시일 때만)를 싣는다. 실제 발행·no-op은 [AnalyticsEventPublisher]가 담당한다.
 *
 * 관측이 비즈니스를 깨지 않도록 모든 발행 경로는 예외를 삼킨다(로그만 남김).
 */
@Component
class ServerAnalytics(
    private val publisher: AnalyticsEventPublisher,
    private val userRepository: UserRepository,
) {

    private val log = LoggerFactory.getLogger(ServerAnalytics::class.java)

    // --- 스토리라인 생성(storyline_generation) ---

    fun storylineGenerationSucceeded(userId: Long?, creationId: Long) =
        emitForUser(EVENT_STORYLINE_SUCCEEDED, userId, mapOf("creation_id" to creationId))

    fun storylineGenerationFailed(userId: Long?, creationId: Long?, errorType: AnalyticsErrorType) =
        emitForUser(
            EVENT_STORYLINE_FAILED,
            userId,
            buildMap {
                creationId?.let { put("creation_id", it) }
                put("error_type", errorType.wireValue)
            },
        )

    // --- 채팅 AI 응답(chat_response) ---

    fun chatAiMessageSucceeded(
        userId: Long?,
        chatId: String,
        turnNumber: Int,
        isRegenerated: Boolean,
        endingId: String? = null,
    ) = emitForUser(
        EVENT_CHAT_SUCCEEDED,
        userId,
        buildMap {
            put("chat_id", chatId)
            put("turn_number", turnNumber)
            put("is_regenerated", isRegenerated)
            endingId?.let { put("ending_id", it) }
        },
    )

    fun chatAiMessageFailed(
        userId: Long?,
        chatId: String,
        turnNumber: Int?,
        errorCode: String?,
        isRegenerated: Boolean,
    ) = emitForUser(
        EVENT_CHAT_FAILED,
        userId,
        buildMap {
            put("chat_id", chatId)
            turnNumber?.let { put("turn_number", it) }
            put("error_type", AnalyticsErrorType.fromAiErrorCode(errorCode).wireValue)
            put("is_regenerated", isRegenerated)
        },
    )

    // --- 피드백 제출 ---

    fun feedbackSubmissionSucceeded(userId: Long?) =
        emitForUser(EVENT_FEEDBACK_SUCCEEDED, userId, emptyMap())

    fun feedbackSubmissionFailed(userId: Long?, errorType: AnalyticsErrorType) =
        emitForUser(EVENT_FEEDBACK_FAILED, userId, mapOf("error_type" to errorType.wireValue))

    // --- 로그인(googleLogin·kakaoLogin) ---

    /** 성공 시엔 이미 해석된 회원 public_id가 있으므로 조회 없이 회원 식별로 발행한다. */
    fun socialLoginSucceeded(provider: SocialProvider, userPublicId: String, isNewUser: Boolean, isRejoined: Boolean = false) =
        loginEvents(provider)?.let { (succeeded, _) ->
            publishSafely(succeeded, userPublicId, mapOf("is_new_user" to isNewUser, "is_rejoined" to isRejoined))
        }

    /** 로그인 실패는 처리 단계와 무관하게 게스트(device_id)로 발행한다. */
    fun socialLoginFailed(provider: SocialProvider, errorType: AnalyticsErrorType) =
        loginEvents(provider)?.let { (_, failed) ->
            publishSafely(failed, userPublicId = null, mapOf("error_type" to errorType.wireValue))
        }

    /**
     * provider별 로그인 이벤트명 (성공, 실패). 이벤트명은 provider마다 갈리며, 기존 googleLogin 이벤트명은
     * 운영에서 발행 중이라 그대로 유지한다(개명하면 대시보드·퍼널이 끊긴다).
     * 로그인 경로가 없는 예약 provider(APPLE·NAVER)는 null이라 발행을 건너뛴다 — 관측이 로그인을 깨지 않아야 한다.
     */
    private fun loginEvents(provider: SocialProvider): Pair<String, String>? = when (provider) {
        SocialProvider.GOOGLE -> EVENT_GOOGLE_LOGIN_SUCCEEDED to EVENT_GOOGLE_LOGIN_FAILED
        SocialProvider.KAKAO -> EVENT_KAKAO_LOGIN_SUCCEEDED to EVENT_KAKAO_LOGIN_FAILED
        else -> null
    }

    // --- 계정 연동(socialLink, KNK-739) ---

    /** 연동 성공. provider는 소문자 표기(`google`·`kakao`)이며 성공에는 error_type을 싣지 않는다. */
    fun socialLinkSucceeded(userId: Long, provider: String) =
        emitForUser(EVENT_LINK_SUCCEEDED, userId, mapOf("provider" to provider))

    /**
     * 연동 실패. 실패 사유는 [AnalyticsErrorType] 3값으로만 구분한다 — 409의 동일/타 회원 여부를 프로퍼티로
     * 나누면 특정 소셜 계정의 가입 여부를 알려주는 신호가 된다. social `sub`·ID 토큰·링크 코드는 싣지 않는다.
     */
    fun socialLinkFailed(userId: Long, provider: String, errorType: AnalyticsErrorType) =
        emitForUser(
            EVENT_LINK_FAILED,
            userId,
            mapOf("provider" to provider, "error_type" to errorType.wireValue),
        )

    // --- 마이그레이션 ---

    fun migrationSucceeded(
        userId: Long?,
        migratedStoryCount: Int,
        migratedChatCount: Int,
        alreadyOwnedCount: Int,
        conflictCount: Int,
        notFoundCount: Int,
    ) = emitForUser(
        EVENT_MIGRATION_SUCCEEDED,
        userId,
        mapOf(
            "migrated_story_count" to migratedStoryCount,
            "migrated_chat_count" to migratedChatCount,
            "already_owned_count" to alreadyOwnedCount,
            "conflict_count" to conflictCount,
            "not_found_count" to notFoundCount,
        ),
    )

    fun migrationFailed(userId: Long?, errorType: AnalyticsErrorType) =
        emitForUser(EVENT_MIGRATION_FAILED, userId, mapOf("error_type" to errorType.wireValue))

    // --- 계정 탈퇴·초대(ACCOUNT §5-4-9) ---

    fun withdrawalSucceeded(userPublicId: String, accountAgeDays: Long, wasSuspended: Boolean) =
        publishAfterCommit(
            EVENT_WITHDRAWAL_SUCCEEDED, userPublicId,
            mapOf("account_age_days" to accountAgeDays, "was_suspended" to wasSuspended),
        )

    fun inviteRedeemSucceeded(userPublicId: String, inviterCapped: Boolean) =
        publishAfterCommit(EVENT_INVITE_SUCCEEDED, userPublicId, mapOf("inviter_capped" to inviterCapped))

    // 자격 거절은 롤백될 요청에서도 판정 시점에 기록한다. 코드 원문과 초대자 식별자는 싣지 않는다.
    fun inviteRedeemFailed(userPublicId: String, reason: InviteRedeemFailureReason) =
        publishSafely(
            EVENT_INVITE_FAILED, userPublicId,
            mapOf("error_type" to AnalyticsErrorType.VALIDATION.wireValue, "reason" to reason.wireValue),
        )

    // --- 출석·결제(PAYMENT §5-4-9) ---

    fun creditAttendanceSucceeded(userId: Long, rewarded: Boolean, amount: Long) =
        emitForUser("server_credit_attendance_processed_succeeded", userId, mapOf("rewarded" to rewarded, "amount" to amount))

    fun paymentOrderSucceeded(userId: Long, productId: String) {
        val publicId = resolvePublicId(userId) ?: return
        publishAfterCommit("server_payment_order_processed_succeeded", publicId, mapOf("product_id" to productId))
    }

    fun paymentPurchaseSucceeded(orderId: Long, userId: Long, productId: String, priceKrw: Long, creditAmount: Long) =
        publishPaymentAfterCommit(
            "server_payment_purchase_processed_succeeded", orderId, userId,
            mapOf("product_id" to productId, "price_krw" to priceKrw, "credit_amount" to creditAmount),
        )

    fun paymentRefundSucceeded(orderId: Long, userId: Long, productId: String, reversalShortfall: Long?) =
        publishPaymentAfterCommit(
            "server_payment_refund_processed_succeeded", orderId, userId,
            buildMap {
                put("product_id", productId)
                reversalShortfall?.let { put("reversal_shortfall", it) }
            },
        )

    private fun publishPaymentAfterCommit(eventType: String, orderId: Long, userId: Long, properties: Map<String, Any?>) {
        afterCommit {
            val publicId = resolvePublicId(userId)
            if (publicId == null) {
                log.warn("분석 이벤트 주문 소유자 식별 실패로 발행 생략: orderId={}", orderId)
            } else {
                publishSafely(eventType, publicId, properties, webhook = true)
            }
        }
    }

    private fun publishAfterCommit(eventType: String, userPublicId: String, properties: Map<String, Any?>) =
        afterCommit { publishSafely(eventType, userPublicId, properties) }

    private fun afterCommit(publish: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = publish()
            })
        } else {
            publish()
        }
    }

    // --- 내부 ---

    /** 내부 [userId](Long)를 public_id로 해석해 발행한다. userId가 null이면 게스트. */
    private fun emitForUser(eventType: String, userId: Long?, properties: Map<String, Any?>) {
        val publicId = if (userId == null) null else resolvePublicId(userId) ?: return
        publishSafely(eventType, publicId, properties)
    }

    private fun resolvePublicId(userId: Long): String? =
        try {
            userRepository.findById(userId).map { it.publicId.toString() }.orElse(null).also {
                if (it == null) log.warn("분석 이벤트 회원 부재로 발행 생략: userId={}", userId)
            }
        } catch (e: Exception) {
            // 회원 이벤트를 게스트 퍼널에 섞지 않는다. 원인 메시지는 개인정보를 포함할 수 있다.
            log.warn("분석 이벤트 user_id 해석 실패로 발행 생략: userId={} exception={}", userId, e.javaClass.simpleName)
            null
        }

    private fun publishSafely(eventType: String, userPublicId: String?, properties: Map<String, Any?>, webhook: Boolean = false) {
        try {
            val rawDeviceHash = if (webhook) null else MDC.get(MdcKeys.DEVICE_ID_HASH)
            val realDeviceHash = rawDeviceHash?.takeIf { it.isNotBlank() && it != UNKNOWN }
            val requestId = MDC.get(MdcKeys.REQUEST_ID)
            val sessionId = if (webhook) null else MDC.get(MdcKeys.SESSION_ID)?.toLongOrNull()
            // 웹훅은 기기 식별자를 싣지 않는다. 그 외에는 원본 헤더를 사용하고, 게스트만 unknown으로 폴백한다.
            val rawDeviceId = if (webhook) null else AnalyticsDeviceContext.currentDeviceId()
            val deviceId = rawDeviceId ?: if (userPublicId == null) UNKNOWN else null
            val eventProperties = buildMap {
                put("is_logged_in", userPublicId != null)
                requestId?.let { put("request_id", it) }
                realDeviceHash?.let { put("device_id_hash", it) }
                putAll(properties)
            }
            publisher.publish(
                AnalyticsEvent(
                    eventType = eventType,
                    userId = userPublicId,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    insertId = "${requestId ?: NO_REQUEST}:$eventType",
                    eventProperties = eventProperties,
                ),
            )
        } catch (e: Exception) {
            log.warn("서버 분석 이벤트 발행 실패: {} ({})", eventType, e.javaClass.simpleName)
        }
    }

    private companion object {
        const val UNKNOWN = "unknown"
        const val NO_REQUEST = "noreq"

        const val EVENT_STORYLINE_SUCCEEDED = "server_storyCreate_storyGeneration_processed_succeeded"
        const val EVENT_STORYLINE_FAILED = "server_storyCreate_storyGeneration_processed_failed"
        const val EVENT_CHAT_SUCCEEDED = "server_chat_aiMessage_processed_succeeded"
        const val EVENT_CHAT_FAILED = "server_chat_aiMessage_processed_failed"
        const val EVENT_FEEDBACK_SUCCEEDED = "server_feedback_submission_processed_succeeded"
        const val EVENT_FEEDBACK_FAILED = "server_feedback_submission_processed_failed"
        const val EVENT_GOOGLE_LOGIN_SUCCEEDED = "server_login_googleLogin_processed_succeeded"
        const val EVENT_GOOGLE_LOGIN_FAILED = "server_login_googleLogin_processed_failed"
        const val EVENT_KAKAO_LOGIN_SUCCEEDED = "server_login_kakaoLogin_processed_succeeded"
        const val EVENT_KAKAO_LOGIN_FAILED = "server_login_kakaoLogin_processed_failed"
        const val EVENT_LINK_SUCCEEDED = "server_link_socialLink_processed_succeeded"
        const val EVENT_LINK_FAILED = "server_link_socialLink_processed_failed"
        const val EVENT_WITHDRAWAL_SUCCEEDED = "server_account_withdrawal_processed_succeeded"
        const val EVENT_INVITE_SUCCEEDED = "server_invite_redeem_processed_succeeded"
        const val EVENT_INVITE_FAILED = "server_invite_redeem_processed_failed"
        const val EVENT_MIGRATION_SUCCEEDED = "server_login_migration_processed_succeeded"
        const val EVENT_MIGRATION_FAILED = "server_login_migration_processed_failed"
    }
}
