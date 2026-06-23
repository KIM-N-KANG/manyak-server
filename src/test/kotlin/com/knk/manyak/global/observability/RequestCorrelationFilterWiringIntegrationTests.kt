package com.knk.manyak.global.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 필터가 실제 서블릿 체인에 등록되어 모든 요청에 동작하는지 검증한다.
 * 비즈니스 로직과 무관하게 항상 200인 actuator health로 echo 동작만 확인한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestCorrelationFilterWiringIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Test
    fun `요청에 X-Manyak-Request-Id가 있으면 응답에 그대로 echo된다`() {
        restTestClient.get()
            .uri("/actuator/health")
            .header("X-Manyak-Request-Id", "req_test_123")
            .header("X-Manyak-Anonymous-Id", "anon-itest")
            .header("X-Manyak-Session-Id", "sess-itest")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Manyak-Request-Id", "req_test_123")
    }

    @Test
    fun `요청에 X-Manyak-Request-Id가 없으면 서버가 생성해 응답에 담는다`() {
        restTestClient.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isOk
            .expectHeader().value("X-Manyak-Request-Id") { assertThat(it).startsWith("req_") }
    }
}
