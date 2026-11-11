package com.knk.manyak.global.security

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

/** 인증된 요청에서 탈퇴 필터가 읽은 회원을 `@CurrentUserId` 해석이 다시 조회하지 않는다(KNK-2068). 결과는 전과 같다. */
class ResolvedUserIdReuseIntegrationTests : IntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: JwtTokenProvider

    @BeforeEach fun clean() = cleaner.cleanAll()

    private fun publicIdLookups() = Mockito.mockingDetails(users).invocations.count { it.method.name == "findByPublicId" }

    @Test
    fun `회원의 필수 경로 요청은 회원을 public_id로 한 번만 읽는다`() {
        val token = tokens.issueAccessToken(users.save(User(nickname = "회원")).publicId)
        Mockito.clearInvocations(users)
        client.get().uri("/api/v1/users/me/credits").header("Authorization", "Bearer $token")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.balance").isEqualTo(0)
        assertThat(publicIdLookups()).isEqualTo(1)
    }

    @Test
    fun `존재하지 않는 회원의 토큰은 전과 같이 필수 경로에서 401이다`() {
        val token = tokens.issueAccessToken(UUID.randomUUID())
        client.get().uri("/api/v1/users/me/credits").header("Authorization", "Bearer $token")
            .exchange().expectStatus().isUnauthorized
    }
}
