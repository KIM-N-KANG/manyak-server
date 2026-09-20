package com.knk.manyak.user.consent

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "게스트 개인정보 수집 및 이용 동의 상태")
data class GuestConsentResponse(val guestPrivacy: ConsentStatusResponse)

@Schema(description = "명시적으로 수락한 게스트 개인정보 수집 및 이용 동의 버전")
data class GuestConsentRequest(
    @field:Schema(description = "수락한 현행 문서 버전", example = "v1.0")
    val guestPrivacy: String? = null,
)
