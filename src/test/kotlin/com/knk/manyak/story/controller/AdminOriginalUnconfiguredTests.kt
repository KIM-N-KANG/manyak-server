package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["manyak.official-user-public-id="])
class AdminOriginalUnconfiguredTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var jwt: JwtTokenProvider

    @Test fun `공식 계정 설정이 비면 등록과 presign은 503이고 목록은 빈 페이지다`() {
        cleaner.cleanAll()
        val actor = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", actor.id)
        val token = jwt.issueAdminToken(actor.publicId)
        val path = "/api/v1/admin/originals"
        client.post().uri(path).header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("title" to "초안", "reason" to "등록")).exchange().expectStatus().isEqualTo(503)
        client.post().uri("$path/images/presign").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("kind" to "COVER", "contentType" to "image/png", "contentLength" to 100)).exchange().expectStatus().isEqualTo(503)
        client.get().uri(path).header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.totalElements").isEqualTo(0).jsonPath("$.totalPages").isEqualTo(0)
    }
}
