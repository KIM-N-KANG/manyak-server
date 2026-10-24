package com.knk.manyak.admin.analytics

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "manyak.admin.langfuse.host=", "manyak.admin.langfuse.public-key=",
    "manyak.admin.langfuse.secret-key=", "manyak.admin.langfuse.environment=",
])
class AdminAiCostUnavailableIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test fun `Langfuse 설정이 없으면 AI 비용만 503이고 DB 지표는 정상이다`() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "비용 관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        val auth = "Bearer ${jwt.issueAdminToken(admin.publicId)}"
        client.get().uri("/api/v1/admin/quality/ai-cost?days=7").header("Authorization", auth)
            .exchange().expectStatus().isEqualTo(503).expectBody()
            .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
        client.get().uri("/api/v1/admin/users/signups?days=7").header("Authorization", auth)
            .exchange().expectStatus().isOk
        client.get().uri("/api/v1/admin/quality/ai-cost?days=367").header("Authorization", auth)
            .exchange().expectStatus().isBadRequest
    }
    @Test fun `설정이 없는 서버도 보관 기간 밖 범위만이면 200과 null asOf다`() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "과거 비용 관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        client.get().uri("/api/v1/admin/quality/ai-cost?from=2026-07-01&to=2026-07-31")
            .header("Authorization", "Bearer ${jwt.issueAdminToken(admin.publicId)}").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.expiredDates.length()").isEqualTo(31).jsonPath("$.pendingDates.length()").isEqualTo(0)
            .jsonPath("$.asOf").isEmpty
    }

}
