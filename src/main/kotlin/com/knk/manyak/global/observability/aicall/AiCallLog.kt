package com.knk.manyak.global.observability.aicall

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * AI 호출 단위 이력(품질·비용·실패).
 *
 * 프롬프트·원문은 저장하지 않고(§6·§8), 식별자·메타와 상태(STARTED → SUCCEEDED/FAILED)만 남겨
 * 서버 로그·저장 데이터와 ai_call_log_id(=this.id)로 연결한다.
 *
 * provider/model/input·outputTokenCount/retryCount/promptVersions는 AI 응답 meta로 채운다([applyMeta]).
 * AI가 meta를 아직 내려주지 않으면(stub 등) 모두 nullable로 비워 둔다.
 * (단일 스칼라 promptTemplateVersion은 chat의 다중 키 버전을 담지 못해, 신규 promptVersions JSONB와 병존한다.)
 */
@Entity
@Table(name = "ai_call_logs")
class AiCallLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "request_id", nullable = false, length = 128)
    val requestId: String,

    @Column(name = "caller_service", nullable = false, length = 50)
    val callerService: String,

    @Convert(converter = AiCallFeatureConverter::class)
    @Column(name = "feature", nullable = false, length = 40)
    val feature: AiCallFeature,

    @Column(name = "anonymous_id_hash", length = 64)
    val anonymousIdHash: String? = null,

    @Column(name = "session_id", length = 128)
    val sessionId: String? = null,

    @Column(name = "story_id")
    val storyId: Long? = null,

    @Column(name = "chat_id")
    val chatId: UUID? = null,

    @Column(name = "turn_index")
    var turnIndex: Int? = null,

    @Column(name = "provider", length = 40)
    var provider: String? = null,

    @Column(name = "model", length = 100)
    var model: String? = null,

    @Column(name = "prompt_template_version", length = 40)
    var promptTemplateVersion: String? = null,

    // AI가 보낸 프롬프트 버전 맵(레이어 키→버전)을 JSONB로 그대로 보관한다.
    // 운영 PostgreSQL은 V12 마이그레이션이 jsonb로, 테스트 H2는 ddl-auto가 dialect별 JSON 타입으로 생성한다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt_versions")
    var promptVersions: Map<String, Int>? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: AiCallStatus = AiCallStatus.STARTED,

    @Column(name = "latency_ms")
    var latencyMs: Long? = null,

    @Column(name = "input_token_count")
    var inputTokenCount: Int? = null,

    @Column(name = "output_token_count")
    var outputTokenCount: Int? = null,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "error_code", length = 100)
    var errorCode: String? = null,

    @Column(name = "sentry_event_id", length = 64)
    var sentryEventId: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null,
) {
    /** 호출 성공으로 전이한다. latency는 호출부에서 측정한 값(ms)을 받는다. */
    fun markSucceeded(
        latencyMs: Long,
        completedAt: Instant = Instant.now(),
    ) {
        this.status = AiCallStatus.SUCCEEDED
        this.latencyMs = latencyMs
        this.completedAt = completedAt
    }

    /**
     * AI 응답 meta를 컬럼에 반영한다.
     *
     * null 필드는 덮어쓰지 않아, 부분 meta가 와도 기존 값(예: retryCount 기본 0)을 보존한다.
     * promptVersions는 AI가 보낸 맵을 그대로 적재한다(레이어 키 변환 없음).
     */
    fun applyMeta(meta: AiCallMeta) {
        meta.model?.let { this.model = it }
        meta.provider?.let { this.provider = it }
        meta.inputTokenCount?.let { this.inputTokenCount = it }
        meta.outputTokenCount?.let { this.outputTokenCount = it }
        meta.retryCount?.let { this.retryCount = it }
        meta.promptVersions?.let { this.promptVersions = it }
    }

    /** 호출 실패로 전이한다. errorCode는 도메인 오류 코드 또는 예외 분류값이다. */
    fun markFailed(
        latencyMs: Long,
        errorCode: String?,
        completedAt: Instant = Instant.now(),
    ) {
        this.status = AiCallStatus.FAILED
        this.latencyMs = latencyMs
        this.errorCode = errorCode
        this.completedAt = completedAt
    }
}
