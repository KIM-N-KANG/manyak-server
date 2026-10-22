package com.knk.manyak.admin.play

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminPlayInstallsIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoBean private lateinit var source: PlayReportsSource
    @Test fun `설치 지표는 관리자만 조회하고 원천 대역의 월별 누계와 null 보간 및 캐시를 반환한다`() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "관리자")); val member = users.save(User(nickname = "회원"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        val path = "/api/v1/admin/dashboard/play-installs"
        client.get().uri(path).exchange().expectStatus().isUnauthorized
        client.get().uri(path).header("Authorization", "Bearer ${jwt.issueAccessToken(admin.publicId)}").exchange().expectStatus().isUnauthorized
        client.get().uri(path).header("Authorization", "Bearer ${jwt.issueAdminToken(member.publicId)}").exchange().expectStatus().isForbidden
        verifyNoInteractions(source)
        `when`(source.reports()).thenReturn(listOf(playCsv(playRow("2026-10-02", 2, 1)), playCsv(playRow("2026-09-30", 29, active = 10))))
        repeat(2) {
            client.get().uri(path).header("Authorization", "Bearer ${jwt.issueAdminToken(admin.publicId)}").exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.asOf").isEqualTo("2026-10-02").jsonPath("$.totalInstalls").isEqualTo(31)
                .jsonPath("$.activeDevices").isEqualTo(11).jsonPath("$.daily.length()").isEqualTo(3)
                .jsonPath("$.daily[1].activeDevices").isEmpty().jsonPath("$.daily[1].cumulativeInstalls").isEqualTo(29)
        }
        verify(source, times(1)).reports()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
        val docs = ObjectMapper().readTree(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(docs["paths"][path]["get"]["responses"].has("200")).isTrue()
        assertThat(docs["components"]["schemas"]["PlayInstallsResponse"]["properties"].has("daily")).isTrue()
    }
}
