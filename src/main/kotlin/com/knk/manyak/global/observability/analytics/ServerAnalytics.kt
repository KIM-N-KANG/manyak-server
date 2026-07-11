package com.knk.manyak.global.observability.analytics

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.MdcKeys
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component

/**
 * 서버 분석 이벤트(스펙 §6-4 `server_*`)를 의미 단위로 발행하는 도메인 파사드(B1, KNK-514).
 *
 * 각 호출부(스토리라인 생성·채팅 응답·피드백·로그인·마이그레이션)는 이 파사드의 타입 안전한 메서드를 호출하고,
 * 이벤트명·프로퍼티·식별자 해석·[AnalyticsErrorType] 매핑은 모두 여기서 한 곳에 모은다.
 *
 * 식별자(§6-2): 회원 요청은 `user_id`=public_id, 게스트는 `device_id`=device_id_hash(MDC). 공통 프로퍼티로
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

    // --- 로그인(googleLogin) ---

    /** 성공 시엔 이미 해석된 회원 public_id가 있으므로 조회 없이 회원 식별로 발행한다. */
    fun googleLoginSucceeded(userPublicId: String, isNewUser: Boolean) =
        publishSafely(EVENT_LOGIN_SUCCEEDED, userPublicId, mapOf("is_new_user" to isNewUser))

    /** 실패는 토큰 검증 단계라 아직 회원이 없다 — 게스트(device_id_hash)로 발행한다. */
    fun googleLoginFailed(errorType: AnalyticsErrorType) =
        publishSafely(EVENT_LOGIN_FAILED, userPublicId = null, mapOf("error_type" to errorType.wireValue))

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

    // --- 내부 ---

    /** 내부 [userId](Long)를 public_id로 해석해 발행한다. userId가 null이면 게스트. */
    private fun emitForUser(eventType: String, userId: Long?, properties: Map<String, Any?>) {
        val publicId = userId?.let { resolvePublicId(it) }
        publishSafely(eventType, publicId, properties)
    }

    private fun resolvePublicId(userId: Long): String? =
        try {
            userRepository.findById(userId).map { it.publicId.toString() }.orElse(null)
        } catch (e: Exception) {
            // public_id 해석 실패로 이벤트 전체를 잃지 않는다 — user_id 없이 발행하도록 null 반환.
            log.warn("분석 이벤트 user_id 해석 실패: userId={} ({})", userId, e.toString())
            null
        }

    private fun publishSafely(eventType: String, userPublicId: String?, properties: Map<String, Any?>) {
        try {
            val rawDeviceHash = MDC.get(MdcKeys.DEVICE_ID_HASH)
            val realDeviceHash = rawDeviceHash?.takeIf { it.isNotBlank() && it != UNKNOWN }
            val requestId = MDC.get(MdcKeys.REQUEST_ID)
            val sessionId = MDC.get(MdcKeys.SESSION_ID)?.toLongOrNull()
            // 회원은 user_id로 귀속하므로 device_id는 실제 해시가 있을 때만(없으면 null). 게스트는 항상 device_id가 있어야
            // Amplitude가 귀속할 수 있으므로 unknown이라도 채운다.
            val deviceId = if (userPublicId != null) {
                realDeviceHash
            } else {
                rawDeviceHash?.takeIf { it.isNotBlank() } ?: UNKNOWN
            }
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
            log.warn("서버 분석 이벤트 발행 실패: {} ({})", eventType, e.toString())
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
        const val EVENT_LOGIN_SUCCEEDED = "server_login_googleLogin_processed_succeeded"
        const val EVENT_LOGIN_FAILED = "server_login_googleLogin_processed_failed"
        const val EVENT_MIGRATION_SUCCEEDED = "server_login_migration_processed_succeeded"
        const val EVENT_MIGRATION_FAILED = "server_login_migration_processed_failed"
    }
}
