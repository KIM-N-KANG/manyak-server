package com.knk.manyak.global.config

import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.junit.jupiter.api.Test

/**
 * OpenAPI 문서(/v3/api-docs)에 Bearer 인증 스킴과 인증 요구가 반영되는지 검증(KNK-450).
 * - components.securitySchemes.bearerAuth: HTTP bearer(JWT).
 * - 인증 필수 엔드포인트(예: GET /users/me/credits)는 operation에 security(bearerAuth)를 갖는다.
 * - 공개 엔드포인트(예: GET /stories/{storyId})는 security가 없다(전역 requirement를 걸지 않았음).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient

    @Test
    fun `OpenAPI 문서에 Bearer 보안 스킴이 등록된다`() {
        restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.components.securitySchemes.bearerAuth.type").isEqualTo("http")
            .jsonPath("$.components.securitySchemes.bearerAuth.scheme").isEqualTo("bearer")
            .jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").isEqualTo("JWT")
    }

    @Test
    fun `인증 필수 엔드포인트는 bearerAuth security를 갖고 공개 엔드포인트는 갖지 않는다`() {
        restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
            // 인증 필수: 크레딧 잔액 조회.
            .jsonPath("$.paths['/api/v1/users/me/credits'].get.security[0].bearerAuth").exists()
            // 공개(permitAll·optional 인증): 스토리 상세 — security를 걸지 않는다.
            .jsonPath("$.paths['/api/v1/stories/{storyId}'].get.security").doesNotExist()
    }
}
