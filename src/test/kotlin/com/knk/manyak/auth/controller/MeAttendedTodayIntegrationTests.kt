package com.knk.manyak.auth.controller

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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 세션 응답(`GET /auth/me`, `PATCH /users/me`)의 `attendedToday`는 회원 id가 아니라 보상 신원의 오늘 출석 키로 판정한다(KNK-1053).
 * 조립이 이미 읽은 회원 행의 보상 신원을 넘겨 `users`를 다시 읽지 않는다(KNK-2091).
 */
class MeAttendedTodayIntegrationTests : IntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: JwtTokenProvider

    @BeforeEach fun clean() = cleaner.cleanAll()

    private fun bearer(user: User) = "Bearer ${tokens.issueAccessToken(user.publicId)}"

    private fun attend(user: User) {
        client.post().uri("/api/v1/users/me/credits/attendance").header("Authorization", bearer(user))
            .exchange().expectStatus().isOk
    }

    private fun me(user: User) = client.get().uri("/api/v1/auth/me").header("Authorization", bearer(user)).exchange()

    private fun patchMe(user: User, nickname: String) = client.patch().uri("/api/v1/users/me").header("Authorization", bearer(user))
        .contentType(MediaType.APPLICATION_JSON).body("""{"nickname":"$nickname"}""").exchange()

    private fun assertAttendedToday(user: User, expected: Boolean) {
        me(user).expectStatus().isOk.expectBody().jsonPath("$.attendedToday").isEqualTo(expected)
        patchMe(user, "출석확인" + user.id).expectStatus().isOk.expectBody().jsonPath("$.attendedToday").isEqualTo(expected)
    }

    @Test
    fun `재가입 회원은 이전 신원이 오늘 출석했으면 true다`() {
        val original = users.save(User(nickname = "최초계정"))
        val rejoined = users.save(User(nickname = "재가입계정", rewardIdentityUserId = original.id))
        attend(original)
        assertAttendedToday(rejoined, true)
    }

    @Test
    fun `재가입 회원은 이전 신원이 오늘 출석하지 않았으면 false다`() {
        val original = users.save(User(nickname = "최초계정"))
        val rejoined = users.save(User(nickname = "재가입계정", rewardIdentityUserId = original.id))
        assertAttendedToday(rejoined, false)
    }

    @Test
    fun `일반 회원은 오늘 출석했으면 true다`() {
        val member = users.save(User(nickname = "일반회원"))
        attend(member)
        assertAttendedToday(member, true)
    }

    @Test
    fun `일반 회원은 오늘 출석하지 않았으면 false다`() {
        assertAttendedToday(users.save(User(nickname = "일반회원")), false)
    }

    @Test
    fun `세션 응답은 출석 판정을 위해 보상 신원을 다시 조회하지 않는다`() {
        val original = users.save(User(nickname = "최초계정"))
        val rejoined = users.save(User(nickname = "재가입계정", rewardIdentityUserId = original.id))
        fun rewardIdentityLookups() = Mockito.mockingDetails(users).invocations.count { it.method.name == "findRewardIdentityUserId" }

        Mockito.clearInvocations(users)
        me(rejoined).expectStatus().isOk
        assertThat(rewardIdentityLookups()).isZero()

        Mockito.clearInvocations(users)
        patchMe(rejoined, "새닉네임").expectStatus().isOk
        assertThat(rewardIdentityLookups()).isZero()
    }
}
