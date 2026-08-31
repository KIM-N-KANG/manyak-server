package com.knk.manyak.credit.service

import com.knk.manyak.credit.entity.CreditPolicy
import com.knk.manyak.credit.repository.CreditPolicyRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * 크레딧 수치 정책의 통합 검증(KNK-1056).
 *
 * 여기서만 볼 수 있는 것: **application.yml의 기본값이 실제로 서비스에 붙는지**. 수치는 팀이 조정하는 값이라
 * 코드 상수가 아니라 yml이 정본이고, 오타나 키 개명은 조용히 "@Value 폴백"으로 가려진다.
 *
 * `credit_policies`에 행이 있을 때의 동작은 [CreditPolicyServiceTest]가 mock으로 본다 — 컨텍스트 싱글턴은
 * 60초 캐시를 이미 채워 둔 상태라, 통합 테스트에서 행을 넣어도 그 순간 반영된다고 보장할 수 없다.
 * 대신 여기서는 저장·재조회가 실 매핑(컬럼명·타입)으로 도는지만 확인한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CreditPolicyServiceIntegrationTest {

    @Autowired private lateinit var creditPolicyService: CreditPolicyService

    @Autowired private lateinit var creditPolicyRepository: CreditPolicyRepository

    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun clean() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `오버라이드 행이 없으면 application_yml 기본값을 쓴다`() {
        assertThat(CreditPolicyKey.entries.associateWith { creditPolicyService.amountOf(it) })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    CreditPolicyKey.SIGNUP_REWARD to 1000L,
                    CreditPolicyKey.INVITE_REWARD to 2000L,
                    CreditPolicyKey.INVITE_MONTHLY_CAP to 10L,
                    CreditPolicyKey.ATTENDANCE_REWARD to 350L,
                    CreditPolicyKey.STORY_CREATION_COST to 200L,
                    CreditPolicyKey.CHAT_TURN_COST to 20L,
                ),
            )
    }

    @Test
    fun `오버라이드 행이 실 매핑으로 저장되고 다시 읽힌다`() {
        creditPolicyRepository.save(CreditPolicy(policyKey = CreditPolicyKey.ATTENDANCE_REWARD.storageKey, amount = 700))

        val saved = creditPolicyRepository.findAll().single()
        assertThat(saved.policyKey).isEqualTo("attendance_reward")
        assertThat(saved.amount).isEqualTo(700)
        assertThat(saved.effectiveUntil).isNull()
    }
}
