package com.knk.manyak.user.controller

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.auth.token.RotateResult
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 회원 탈퇴(KNK-1019). soft delete(DELETED) + 개인정보 익명화 + 소셜 연결 삭제 + refresh 전체 폐기.
 * 소유 스토리는 공개 상태를 유지한다(2026-08-30 팀 결정 — 작성자 표기는 익명화된 닉네임이 자연 반영).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserWithdrawalIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var socialAccountRepository: SocialAccountRepository
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var refreshTokenStore: RefreshTokenStore
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveMember(nickname: String = "탈퇴예정자"): User =
        userRepository.save(User(nickname = nickname, profileImageUrl = "https://example.com/p.png", status = UserStatus.ACTIVE))

    private fun tokenFor(user: User): String = jwtTokenProvider.issueAccessToken(user.publicId)

    @Test
    fun `탈퇴하면 DELETED 전환과 함께 개인정보가 익명화되고 소셜 연결·refresh가 폐기된다`() {
        val user = saveMember()
        socialAccountRepository.save(SocialAccount(userId = user.id, provider = SocialProvider.GOOGLE, providerUserId = "google-sub-1"))
        refreshTokenStore.createFamily("refresh-hash-1", user.id, Duration.ofHours(1))

        restTestClient.delete().uri("/api/v1/users/me")
            .header("Authorization", "Bearer ${tokenFor(user)}")
            .exchange()
            .expectStatus().isNoContent

        val reloaded = userRepository.findById(user.id).get()
        assertEquals(UserStatus.DELETED, reloaded.status)
        org.junit.jupiter.api.Assertions.assertNotNull(reloaded.deletedAt)
        assertEquals("탈퇴한 사용자", reloaded.nickname)
        assertNull(reloaded.profileImageUrl)
        assertNull(reloaded.profileThumbnailBase64)
        assertEquals(0, socialAccountRepository.count())
        // refresh family 전체 폐기 — 남은 토큰으로 회전하면 Invalid다.
        assertTrue(refreshTokenStore.rotate("refresh-hash-1", "new-hash", Duration.ofHours(1)) is RotateResult.Invalid)
    }

    @Test
    fun `탈퇴 후 만료 전 access 토큰의 쓰기 요청은 401이다`() {
        val user = saveMember()
        val story = storyRepository.save(
            Story(title = "탈퇴자 공개 스토리", userId = user.id, visibility = StoryVisibility.PUBLIC, status = StoryStatus.PUBLISHED),
        )
        val token = tokenFor(user)

        restTestClient.delete().uri("/api/v1/users/me")
            .header("Authorization", "Bearer $token")
            .exchange().expectStatus().isNoContent

        // 잔여 access 토큰의 쓰기 경로는 공통 게이트가 401로 막는다(§4-5 B20 확장).
        restTestClient.post().uri("/api/v1/stories/${story.publicId}/like")
            .header("Authorization", "Bearer $token")
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `탈퇴해도 공개 스토리는 유지되고 작성자는 익명화된 닉네임으로 보인다`() {
        val user = saveMember(nickname = "원래닉네임")
        val story = storyRepository.save(
            Story(title = "남는 공개 스토리", userId = user.id, visibility = StoryVisibility.PUBLIC, status = StoryStatus.PUBLISHED),
        )

        restTestClient.delete().uri("/api/v1/users/me")
            .header("Authorization", "Bearer ${tokenFor(user)}")
            .exchange().expectStatus().isNoContent

        // 공개 상태 유지(팀 결정) — 게스트도 계속 읽을 수 있고, author는 익명화된 닉네임이다.
        restTestClient.get().uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.author.nickname").isEqualTo("탈퇴한 사용자")
            .jsonPath("$.visibility").isEqualTo("PUBLIC")
    }

    @Test
    fun `탈퇴한 계정의 잔여 access 토큰은 전면 401이다(재탈퇴·무가드 쓰기 경로 포함)`() {
        // 탈퇴 후 잔여 토큰은 해석 계층(CurrentUserIdArgumentResolver)에서 전면 무효다(KNK-1019 Codex P1).
        // SuspensionGuard를 거치지 않는 인증 쓰기 경로(출석 크레딧 등)까지 한 번에 닫힌다.
        val user = saveMember()
        val token = tokenFor(user)

        restTestClient.delete().uri("/api/v1/users/me")
            .header("Authorization", "Bearer $token")
            .exchange().expectStatus().isNoContent

        restTestClient.delete().uri("/api/v1/users/me")
            .header("Authorization", "Bearer $token")
            .exchange().expectStatus().isUnauthorized

        restTestClient.post().uri("/api/v1/users/me/credits/attendance")
            .header("Authorization", "Bearer $token")
            .exchange().expectStatus().isUnauthorized

        // 리졸버를 우회해 Jwt를 직접 소비하는 auth/me도 같은 계약이다(KNK-1019 Codex P1).
        restTestClient.get().uri("/api/v1/auth/me")
            .header("Authorization", "Bearer $token")
            .exchange().expectStatus().isUnauthorized

        // @CurrentUserId를 아예 안 쓰는 공개 조회(optional 인증)도 필터가 같은 계약을 집행한다(4차 P2).
        restTestClient.get().uri("/api/v1/stories/00000000-0000-0000-0000-000000000000")
            .header("Authorization", "Bearer $token")
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `미인증 탈퇴 요청은 401이다`() {
        restTestClient.delete().uri("/api/v1/users/me")
            .exchange().expectStatus().isUnauthorized
    }
}
