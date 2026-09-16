package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.credit.service.GuestTrialLimitService.Counter
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrialControllerIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var trials: GuestTrialLimitService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @BeforeEach fun reset() = cleaner.cleanAll()

    @Test fun `게스트는 헤더가 필수이고 디바이스 카운터 네 종류를 조회한다`() {
        client.get().uri("/api/v1/users/me/trials").exchange().expectStatus().isBadRequest
        trials.reserve("trials-device", Counter.CHAT_IMAGE)
        client.get().uri("/api/v1/users/me/trials").header("X-Manyak-Device-Id", "trials-device")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.chatImage.used").isEqualTo(1).jsonPath("$.chatImage.limit").isEqualTo(5)
            .jsonPath("$.chatTurn.used").isEqualTo(0).jsonPath("$.chatTurn.limit").isEqualTo(5)
            .jsonPath("$.storyCreation.limit").isEqualTo(1).jsonPath("$.storylineGeneration.limit").isEqualTo(5)
            .jsonPath("$.remaining").doesNotExist()
    }
    @Test fun `회원은 헤더 없이 회원 카운터를 조회하고 스토리라인은 무제한이다`() {
        val user = users.save(User(nickname = "체험회원"))
        trials.reserveMember(user.id, Counter.CHAT_IMAGE)
        client.get().uri("/api/v1/users/me/trials").header("Authorization", "Bearer ${jwt.issueAccessToken(user.publicId)}")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.chatImage.used").isEqualTo(1).jsonPath("$.chatTurn.used").isEqualTo(0)
            .jsonPath("$.storylineGeneration.used").isEqualTo(0).jsonPath("$.storylineGeneration.limit").isEmpty
            .jsonPath("$.chatImage.remaining").doesNotExist()
    }
    @Test fun `오래된 토큰은 게스트 헤더로 조회한다`() {
        client.get().uri("/api/v1/users/me/trials").header("Authorization", "Bearer expired")
            .header("X-Manyak-Device-Id", "trials-device").exchange().expectStatus().isOk
    }
}
