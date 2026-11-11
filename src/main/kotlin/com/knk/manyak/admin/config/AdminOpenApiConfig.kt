package com.knk.manyak.admin.config

import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 관리자 응답 스키마의 OpenAPI 보정. 스키마 소유 모듈이 정의한다(KNK-2068). */
@Configuration
class AdminOpenApiConfig {

    // 관리자 스토리 설정(ADMIN 4-2-7) 응답은 모든 필드가 항상 실린다. 관리자 웹이 OpenAPI로 타입을 생성하므로
    // springdoc이 내지 않는 required를 이 응답 스키마에만 채운다. null 허용은 Kotlin nullable이 그대로 낸다.
    @Bean
    fun adminStorySettingsRequired(): OpenApiCustomizer = OpenApiCustomizer { api ->
        api.components?.schemas?.filterKeys { it.startsWith("AdminStorySettings") }?.values?.forEach { schema ->
            schema.required = schema.properties?.keys?.toList()
        }
    }

    // ADMIN 4-2-48도 nullable 평균을 포함해 응답 필드가 항상 존재한다.
    @Bean
    fun adminActivityHoursRequired(): OpenApiCustomizer = OpenApiCustomizer { api ->
        api.components?.schemas?.filterKeys { it.startsWith("AdminActivityHours") }?.values?.forEach { schema ->
            schema.required = schema.properties?.keys?.toList()
        }
    }
}
