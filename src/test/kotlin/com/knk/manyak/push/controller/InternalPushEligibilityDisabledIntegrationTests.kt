package com.knk.manyak.push.controller

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.internal.shared-secret="],
)
class InternalPushEligibilityDisabledIntegrationTests {
    @Autowired private lateinit var client: RestTestClient

    @ParameterizedTest
    @ValueSource(strings = ["", "?kind=SERVICE&at=2026-09-21T03:00:00Z"])
    fun `시크릿 미설정이면 인증과 파라미터에 앞서 404다`(query: String) {
        val path = "/internal/users/${UUID.randomUUID()}/push-eligibility$query"
        client.get().uri(path).exchange().expectStatus().isNotFound
        client.get().uri(path).header("X-Manyak-Internal-Secret", "arbitrary-secret")
            .header("Authorization", "Bearer invalid").exchange().expectStatus().isNotFound
    }
}
