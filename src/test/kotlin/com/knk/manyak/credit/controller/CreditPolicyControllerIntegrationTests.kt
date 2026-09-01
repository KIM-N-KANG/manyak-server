package com.knk.manyak.credit.controller

import com.knk.manyak.credit.entity.CreditPolicy
import com.knk.manyak.credit.repository.CreditPolicyRepository
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * GET /api/v1/credits/policies 통합 검증.
 *
 * 화면이 "출석하면 얼마 받는지"를 누르기 전에 보여주려면 지금 유효한 수치를 읽어야 한다. 수치는
 * `credit_policies` 오버라이드로 릴리스 없이 바뀌므로(KNK-1056), 이 엔드포인트가 **yml 기본값이 아니라
 * 그때그때 유효값**을 준다는 점이 계약의 핵심이다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreditPolicyControllerIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var creditPolicyRepository: CreditPolicyRepository
    @Autowired private lateinit var creditPolicyService: CreditPolicyService
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    @AfterEach
    fun resetPolicies() {
        // 공유 컨텍스트의 스냅샷이 오버라이드를 들고 다른 테스트로 새지 않도록 앞뒤로 비운다.
        databaseCleaner.cleanAll()
        creditPolicyService.refresh()
    }

    @Test
    fun `인증 없이 조회하면 현재 수치를 반환한다`() {
        restTestClient.get()
            .uri("/api/v1/credits/policies")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.signupReward").isEqualTo(1000)
            .jsonPath("$.inviteReward").isEqualTo(2000)
            .jsonPath("$.inviteMonthlyCap").isEqualTo(10)
            .jsonPath("$.attendanceReward").isEqualTo(350)
            .jsonPath("$.storyCreationCost").isEqualTo(200)
            .jsonPath("$.chatTurnCost").isEqualTo(20)
    }

    @Test
    fun `오버라이드가 걸리면 그 값을 반환한다`() {
        creditPolicyRepository.save(
            CreditPolicy(policyKey = CreditPolicyKey.ATTENDANCE_REWARD.storageKey, amount = 700),
        )
        creditPolicyService.refresh()

        restTestClient.get()
            .uri("/api/v1/credits/policies")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.attendanceReward").isEqualTo(700)
    }

    @Test
    fun `만료·위조 access 토큰이 붙어도 401이 아니다`() {
        // 클라이언트가 모든 요청에 토큰을 자동 첨부하는 구성에서, 공개 경로가 stale 헤더로 막히면
        // 로그아웃 상태의 안내 화면이 통째로 깨진다(SecurityConfig의 토큰 resolve 스킵이 이걸 막는다).
        restTestClient.get()
            .uri("/api/v1/credits/policies")
            .header("Authorization", "Bearer not-a-real-token")
            .exchange()
            .expectStatus().isOk
    }
}
