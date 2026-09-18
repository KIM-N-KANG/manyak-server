package com.knk.manyak.user.consent

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "문서의 현행 버전과 해당 버전에 대한 동의 필요 여부")
data class ConsentStatusResponse(
    @field:Schema(description = "동의가 필요한 현행 버전", example = "v1.2")
    val requiredVersion: String,
    @field:Schema(description = "현행 버전의 동의 이력이 없으면 true", example = "true")
    val needsConsent: Boolean,
)

@Schema(description = "이용약관·개인정보 처리방침·만 14세 이상 확인 동의 상태")
data class UserConsentResponse(
    val terms: ConsentStatusResponse,
    val privacy: ConsentStatusResponse,
    val age14: ConsentStatusResponse,
)

@Schema(description = "명시적으로 수락한 버전만 제출. 최소 한 항목 필수이며 누락·null은 미제출")
data class UserConsentRequest(
    @field:Schema(description = "수락한 이용약관 버전", example = "v1.2")
    val terms: String? = null,
    @field:Schema(description = "수락한 개인정보 처리방침 버전", example = "v1.4")
    val privacy: String? = null,
    @field:Schema(description = "만 14세 이상 확인 버전(1 고정)", example = "1")
    val age14: String? = null,
)
