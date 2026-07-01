package com.knk.manyak.global.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

/**
 * KNK-321: 운영(prod) 프로파일에서 Swagger UI·OpenAPI 문서가 비공개화되는지 가드한다.
 *
 * springdoc 경로(/swagger-ui.html, /v3/api-docs)는 기본값이라 사실상 고정이고 SecurityConfig에서 permitAll이다.
 * api.manyak.app 같은 추측 가능한 주소로 외부에서 전체 API 스펙이 노출되므로, 운영에서는 문서를 끈다.
 * 이 설정이 application-prod.yml에서 빠지면(회귀) 다시 공개되므로, 프로파일 yml 내용을 직접 검증한다.
 */
class SwaggerDocsProdConfigTests {

    private val prodProperties = YamlPropertiesFactoryBean()
        .apply { setResources(ClassPathResource("application-prod.yml")) }
        .getObject()!!

    @Test
    fun `운영 프로파일은 OpenAPI 문서(api-docs)를 비활성화한다`() {
        assertThat(prodProperties.getProperty("springdoc.api-docs.enabled")).isEqualTo("false")
    }

    @Test
    fun `운영 프로파일은 Swagger UI를 비활성화한다`() {
        assertThat(prodProperties.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false")
    }
}
