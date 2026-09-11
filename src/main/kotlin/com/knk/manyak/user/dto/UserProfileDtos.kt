package com.knk.manyak.user.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 프로필 수정 요청(KNK-1147). **보낸 필드만 반영**하고 둘 다 없으면 400이다.
 *
 * 두 값 모두 지울 수 없는 값이라 `null`은 "지우기"가 아니라 "안 보냄"이다 — 둘 다 null이면 바꿀 것이 없어
 * 400으로 거부한다.
 */
@Schema(description = "프로필 수정 요청(KNK-1147). 둘 중 최소 하나는 있어야 한다")
data class UpdateProfileRequest(
    @field:Schema(
        description = "새 닉네임. 앞뒤 공백을 지운 뒤 2~20자이며 한글·영문·숫자·공백만 쓸 수 있다. " +
            "대소문자·공백만 다른 닉네임은 이미 쓰는 것으로 본다(409).",
        example = "몽환적인 이야기꾼",
        nullable = true,
    )
    val nickname: String? = null,

    @field:Schema(
        description = "프로필 이미지 프리셋 키(명사). `GET /api/v1/profile-presets`가 주는 값이며 업로드는 없다.",
        example = "이야기꾼",
        nullable = true,
    )
    val profileImagePreset: String? = null,
)

/** 프로필 이미지 프리셋 선택지(KNK-1147). */
@Schema(description = "프로필 이미지 프리셋")
data class ProfilePresetResponse(
    @field:Schema(description = "프리셋 키(명사). 프로필 수정 요청의 profileImagePreset에 그대로 넣는다", example = "이야기꾼")
    val key: String,

    @field:Schema(description = "원본 이미지 URL(256×256)", example = "https://api.manyak.app/profile-presets/%EC%9D%B4%EC%95%BC%EA%B8%B0%EA%BE%BC.png")
    val imageUrl: String,

    @field:Schema(description = "48×48 저해상도 인라인 썸네일(base64). 목록 첫 페인트용", nullable = true)
    val thumbnailBase64: String?,
)
