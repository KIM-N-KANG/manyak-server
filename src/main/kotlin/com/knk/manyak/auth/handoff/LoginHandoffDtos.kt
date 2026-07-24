package com.knk.manyak.auth.handoff

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

/** 핸드오프에 담을 수 있는 ID 배열 상한. 이관 요청 상한(100)과 동일하다. */
const val MAX_HANDOFF_IDS = 100

/**
 * 앱 내 상대 경로만 허용하는 복귀 경로 패턴(오픈 리다이렉트 차단).
 * `/`로 시작하되 `//`·`/\`(프로토콜 상대 URL)은 막고, URL 파서가 제거해 우회를 만드는 제어 문자도 거른다.
 * 프론트엔드의 `resolveLoginCallbackUrl`과 같은 규칙이지만, 신뢰 경계인 서버에서 독립적으로 강제한다.
 */
private const val APP_RELATIVE_PATH = "^/(?![/\\\\])[^\\x00-\\x1F]*$"

/** 핸드오프를 만든 인앱 브라우저 종류(분석용 태그). 스펙 §4-3-5가 고정한 세 값만 허용한다. */
private const val SOURCE_APP_VALUES = "^(kakaotalk|instagram|threads)$"

@Schema(description = "로그인 핸드오프 생성 요청")
data class LoginHandoffCreateRequest(
    @field:Size(max = MAX_HANDOFF_IDS, message = "storyIds는 최대 100개까지 허용합니다.")
    @field:Schema(description = "이관 대상 스토리 공개 ID(UUID) 목록. 최대 100개, 빈 배열 허용")
    val storyIds: List<String> = emptyList(),

    @field:Size(max = MAX_HANDOFF_IDS, message = "chatIds는 최대 100개까지 허용합니다.")
    @field:Schema(description = "이관 대상 채팅 공개 ID(UUID) 목록. 최대 100개, 빈 배열 허용")
    val chatIds: List<String> = emptyList(),

    @field:NotBlank(message = "복귀 경로는 필수입니다.")
    @field:Pattern(regexp = APP_RELATIVE_PATH, message = "복귀 경로는 앱 내 상대 경로여야 합니다.")
    @field:Schema(description = "로그인 후 복귀할 앱 내 상대 경로", example = "/chats/3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val callbackPath: String,

    @field:Pattern(regexp = SOURCE_APP_VALUES, message = "지원하지 않는 인앱 브라우저입니다.")
    @field:Schema(description = "핸드오프를 만든 인앱 브라우저", allowableValues = ["kakaotalk", "instagram", "threads"])
    val sourceApp: String,
)

@Schema(description = "로그인 핸드오프 생성 결과")
data class LoginHandoffCreateResponse(
    @field:Schema(description = "일회용 핸드오프 코드. 이 응답에서만 노출되며 이후 헤더로만 제시한다. 로그·분석 이벤트에 남기지 않는다.")
    val handoffCode: String,

    @field:Schema(description = "분석 전용 식별자. 비밀값이 아니며 인앱·외부 브라우저 퍼널을 잇는 데 쓴다(스펙 §6-4-2-12).")
    val handoffId: String,

    @field:Schema(description = "핸드오프 만료 시각")
    val expiresAt: Instant,
)

@Schema(description = "로그인 핸드오프 확인 결과(외부 랜딩 안내용)")
data class LoginHandoffSummaryResponse(
    @field:Schema(description = "옮길 스토리 건수", example = "1")
    val storyCount: Int,

    @field:Schema(description = "옮길 채팅 건수", example = "1")
    val chatCount: Int,

    @field:Schema(description = "로그인 후 복귀할 앱 내 상대 경로. 외부 브라우저는 이 응답으로만 복귀 경로를 알 수 있다.")
    val callbackPath: String,

    @field:Schema(description = "핸드오프 만료 시각")
    val expiresAt: Instant,
)

@Schema(description = "로그인 핸드오프 상태(인앱 복귀 정리용)")
data class LoginHandoffStatusResponse(
    val status: LoginHandoffStatus,

    @field:Schema(description = "이번 핸드오프로 실제 이관된 스토리 공개 ID. 인앱은 이 ID만 로컬에서 제거한다.")
    val migratedStoryIds: List<String>,

    @field:Schema(description = "이번 핸드오프로 실제 이관된 채팅 공개 ID. 인앱은 이 ID만 로컬에서 제거한다.")
    val migratedChatIds: List<String>,
)

@Schema(description = "로그인 핸드오프 상태")
enum class LoginHandoffStatus {
    /** 생성됨 — 외부 브라우저가 아직 받지 않음 */
    PENDING,

    /** 외부 랜딩이 코드를 수령함(확인 호출) */
    LANDED,

    /** 소비 완료 — 이관 결과 ID 목록 포함(성공 0건 포함) */
    MIGRATED,

    /** 소비했으나 계정 잠금·시도 상한으로 이관되지 않음 */
    MIGRATION_CLOSED,
}
