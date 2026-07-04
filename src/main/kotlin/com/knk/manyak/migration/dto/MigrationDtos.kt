package com.knk.manyak.migration.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/** 마이그레이션 요청 배열 상한. 로컬 서재 상한(100)과 동일하다. */
const val MAX_MIGRATION_IDS = 100

@Schema(description = "게스트 데이터 마이그레이션 요청")
data class MigrationRequest(
    @field:Size(max = MAX_MIGRATION_IDS, message = "storyIds는 최대 100개까지 허용합니다.")
    @Schema(description = "이관할 스토리 공개 ID(UUID) 목록. 최대 100개, 빈 배열 허용")
    val storyIds: List<String> = emptyList(),

    @field:Size(max = MAX_MIGRATION_IDS, message = "chatIds는 최대 100개까지 허용합니다.")
    @Schema(description = "이관할 채팅 공개 ID(UUID) 목록. 최대 100개, 빈 배열 허용")
    val chatIds: List<String> = emptyList(),
)

@Schema(description = "항목별 이관 결과 상태")
enum class MigrationStatus {
    /** 이번 요청으로 소유권이 설정됨 */
    MIGRATED,

    /** 이미 요청자 소유(재호출 등) */
    ALREADY_OWNED,

    /** 다른 회원 소유 — 이관하지 않음 */
    CONFLICT,

    /** 존재하지 않거나 삭제됨 */
    NOT_FOUND,
}

@Schema(description = "항목별 이관 결과")
data class MigrationResult(
    @Schema(description = "요청한 공개 ID(UUID)")
    val id: String,
    val status: MigrationStatus,
)

@Schema(description = "게스트 데이터 마이그레이션 결과")
data class MigrationResponse(
    val stories: List<MigrationResult>,
    val chats: List<MigrationResult>,
)
