package com.knk.manyak.push.dto

import com.knk.manyak.push.entity.PushPlatform
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "디바이스 푸시 토큰 등록 요청(KNK-1131)")
data class PushTokenRegisterRequest(
    @field:NotBlank
    @field:Size(max = 512)
    @field:Schema(
        description = "앱이 FCM에서 발급받은 등록 토큰. 같은 값을 다시 보내면 갱신(멱등)이고, " +
            "토큰이 바뀌면(onNewToken) 새 값으로 다시 등록한다.",
        example = "dEv1cE:APA91bExampleToken-0123456789",
    )
    val token: String,

    @field:Schema(description = "기기 플랫폼", example = "ANDROID")
    val platform: PushPlatform,
)
