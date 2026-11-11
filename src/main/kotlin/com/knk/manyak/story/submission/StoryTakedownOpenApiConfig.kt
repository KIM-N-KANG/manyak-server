package com.knk.manyak.story.submission

import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 공개 중단 요청 스키마의 OpenAPI 보정. 스키마 소유 모듈이 정의한다(KNK-2068). */
@Configuration
class StoryTakedownOpenApiConfig {

    // MODERATION 4-2-10의 조건부 입력 규칙. 서비스 검증은 유지하고 문서에만 oneOf를 더한다.
    @Bean
    fun storyTakedownSchema(): OpenApiCustomizer = OpenApiCustomizer { api ->
        val schema = api.components?.schemas?.get(StoryTakedownRequest::class.java.simpleName)
            ?: return@OpenApiCustomizer
        val predefined = ObjectSchema()
            .addProperty("reasonCode", StringSchema()._enum(listOf("SEXUAL_CONTENT", "COPYRIGHT_INFRINGEMENT", "HATE_SPEECH")))
            .addProperty("reasonDetail", Schema<Any>().types(linkedSetOf("null")))
            .required(listOf("reasonCode"))
        val other = ObjectSchema()
            .addProperty("reasonCode", StringSchema()._enum(listOf("ETC")))
            .addProperty("reasonDetail", StringSchema().minLength(1).maxLength(100).pattern("(?sU).*\\S.*"))
            .required(listOf("reasonCode", "reasonDetail"))
        schema.oneOf = listOf(predefined, other)
    }
}
