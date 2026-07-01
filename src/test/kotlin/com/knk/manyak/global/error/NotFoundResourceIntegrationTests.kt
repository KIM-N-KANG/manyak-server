package com.knk.manyak.global.error

import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * KNK-349: permitAll이지만 핸들러가 없는 경로는 500이 아니라 404여야 한다.
 *
 * 운영은 springdoc(swagger/api-docs)을 끄는데(KNK-321, application-prod.yml), 그 경로는 SecurityConfig에서
 * permitAll이라 보안을 통과한 뒤 핸들러가 없어 미매핑 예외가 발생한다. 이 예외가 GlobalExceptionHandler의
 * catch-all(Exception)로 떨어지면 500 + Sentry 이벤트가 되어, 스캐너의 /swagger-ui 폴링마다 5xx 에러
 * 노이즈가 쌓인다. 미존재 리소스는 표준대로 404(NOT_FOUND)로 응답해야 한다.
 *
 * prod 프로파일 대신 test 프로파일에서 springdoc만 비활성화해 동일 상황(permitAll + 핸들러 없음)을 재현한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
    ],
)
class NotFoundResourceIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        // 컨텍스트가 Redis 인프라 없이 뜨도록 in-memory 저장소로 대체한다(다른 통합 테스트와 동일).
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Test
    fun `springdoc 비활성 시 api-docs 경로는 404다`() {
        restTestClient.get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `springdoc 비활성 시 swagger-ui 경로는 404다`() {
        restTestClient.get()
            .uri("/swagger-ui.html")
            .exchange()
            .expectStatus().isNotFound
    }
}
