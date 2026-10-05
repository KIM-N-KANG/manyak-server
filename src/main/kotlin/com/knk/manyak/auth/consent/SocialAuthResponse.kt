package com.knk.manyak.auth.consent

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.user.consent.UserConsentResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "COMPLETED이면 token, CONSENT_REQUIRED이면 대기 코드와 필수 동의 상태만 반환")
data class SocialAuthResponse(
    @field:Schema(allowableValues = ["COMPLETED", "CONSENT_REQUIRED"], example = "CONSENT_REQUIRED")
    val status: String,
    val token: TokenResponse? = null,
    @field:Schema(description = "완료 API 전용 불투명 코드. Bearer 토큰이 아님")
    val consentToken: String? = null,
    val expiresAt: Instant? = null,
    @get:JsonProperty("isNewUser")
    val isNewUser: Boolean? = null,
    val consents: UserConsentResponse? = null,
)
