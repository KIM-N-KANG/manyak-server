package com.knk.manyak.chat.config

import com.knk.manyak.chat.dto.ChangeChatSettingsRequest
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 채팅 요청 스키마의 OpenAPI 보정. 스키마 소유 모듈이 정의한다(KNK-2068). */
@Configuration
class ChatOpenApiConfig {

    // springdoc 3.0.3의 KotlinNullablePropertyCustomizer는 @Schema의 types/nullable과 무관하게
    // Kotlin nullable 프로퍼티에 null 타입을 추가한다. Nulls.FAIL로 null을 거절하는 이 요청만 보정한다.
    @Bean
    fun chatSettingsSchema(): OpenApiCustomizer = OpenApiCustomizer { api ->
        val schema = api.components?.schemas?.get(ChangeChatSettingsRequest::class.java.simpleName)
            ?: return@OpenApiCustomizer
        for (name in listOf("model", "longResponse")) {
            val property = schema.properties?.get(name) ?: continue
            property.types = property.types?.filterTo(linkedSetOf()) { it != "null" }
            property.nullable = false
        }
    }
}
