package com.knk.manyak.feedback.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(description = "피드백 등록 요청")
data class CreateFeedbackRequest(
    @field:NotBlank
    @field:Size(max = 2000)
    @field:Schema(
        description = "피드백 본문. 사용자가 자유롭게 작성합니다.",
        example = "이야기 진행 중 가끔 응답이 끊겨요. 그래도 재미있게 쓰고 있습니다!",
    )
    val body: String,

    @field:Email
    @field:Size(max = 320)
    @field:Schema(
        description = "답변을 받고 싶은 경우에만 입력하는 선택 이메일. 비워두면 익명 피드백으로 처리됩니다.",
        example = "user@example.com",
        nullable = true,
    )
    val email: String? = null,

    @field:Pattern(
        regexp = "IOS|ANDROID|WEB",
        message = "platform 은 IOS, ANDROID, WEB 중 하나여야 합니다.",
    )
    @field:Schema(
        description = "피드백을 보낸 플랫폼. 앱이 화면 입력 없이 자동으로 채워 보냅니다.",
        example = "IOS",
        allowableValues = ["IOS", "ANDROID", "WEB"],
        nullable = true,
    )
    val platform: String? = null,

    @field:Size(max = 50)
    @field:Schema(
        description = "피드백을 보낸 앱 버전. 앱이 화면 입력 없이 자동으로 채워 보냅니다.",
        example = "1.2.0",
        nullable = true,
    )
    val appVersion: String? = null,
)
