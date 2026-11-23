package com.knk.manyak.global.observability

import com.knk.manyak.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper

/**
 * 배포된 이미지가 어느 커밋인지 공개 주소로 확인한다(KNK-2126). OCI 운영 호스트는 호스트가 이미지를 받아 배포하므로,
 * CI는 `/actuator/info`의 커밋으로 배포 완료를 본다. 값은 빌드 때 `bootBuildInfo`가 넣는다.
 */
class ActuatorInfoIntegrationTests : IntegrationTest() {
    @Autowired private lateinit var client: RestTestClient

    @Test
    fun `info는 토큰 없이 빌드 정보와 커밋을 돌려준다`() {
        val body = client.get().uri("/actuator/info").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val build = ObjectMapper().readTree(body)["build"]
        assertThat(build).describedAs(body).isNotNull()
        assertThat(build["artifact"].asText()).isEqualTo("manyak-server")
        assertThat(build["commit"].asText()).matches("[0-9a-f]{40}|unknown")
    }
}
