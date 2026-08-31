package com.knk.manyak.credit.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditPolicy
import com.knk.manyak.credit.repository.CreditPolicyRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 정책 읽기가 **호출자 트랜잭션 안에서 DB I/O를 하지 않는지** 고정한다(KNK-1056, Codex 리뷰 P2 두 라운드).
 *
 * 크레딧 경로는 `@Transactional` 안에서 정책을 읽는다(출석 적립·초대 제출·턴 차감). 거기서 DB를 만지면
 * 두 가지가 따라온다: 조회 실패가 바깥을 rollback-only로 찍어 **기본값 폴백이 있어도 커밋이 실패**하고,
 * `REQUIRES_NEW`로 그걸 피하면 바깥 커넥션을 쥔 채 **두 번째 커넥션을 기다려** 풀 포화 시 돈 경로가
 * connection timeout까지 멈춘다. 그래서 적재는 부팅 선적재·주기 스케줄러만 하고 읽기는 메모리 연산이다.
 *
 * 검증은 mock이 아니라 **테이블을 통째로 치우는** 방식이다: `credit_policies`가 없는 상태에서 트랜잭션 안의
 * 읽기가 (1) 예외 없이 (2) 적재해 둔 오버라이드 값을 그대로 주고 (3) 그 트랜잭션이 정상 커밋되면,
 * 그 읽기는 DB를 만지지 않았다는 뜻이다. 누군가 읽기 경로에 조회를 되살리면 SQL 실패가 바깥 트랜잭션을
 * 오염시켜 커밋이 UnexpectedRollbackException으로 깨진다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CreditPolicyTransactionIsolationIntegrationTest {

    @Autowired private lateinit var creditPolicyService: CreditPolicyService

    @Autowired private lateinit var creditPolicyRepository: CreditPolicyRepository

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun clean() {
        databaseCleaner.cleanAll()
        // 공유 컨텍스트의 스냅샷이 앞 테스트의 오버라이드를 들고 있을 수 있어 빈 테이블 상태로 맞춘다.
        creditPolicyService.refresh()
    }

    @Test
    fun `정책 테이블이 없어도 트랜잭션 안의 정책 읽기는 메모리에서 답하고 그 트랜잭션은 정상 커밋된다`() {
        creditPolicyRepository.save(
            CreditPolicy(policyKey = CreditPolicyKey.ATTENDANCE_REWARD.storageKey, amount = 700),
        )
        creditPolicyService.refresh()
        val transactionTemplate = TransactionTemplate(transactionManager)
        // credit_policies 를 통째로 치운다(H2 DDL 은 자동 커밋이라 바깥 트랜잭션 밖에서 한다).
        jdbcTemplate.execute("ALTER TABLE credit_policies RENAME TO credit_policies_hidden")
        val savedId = try {
            transactionTemplate.execute {
                val user = userRepository.save(User(nickname = "정책메모리회원", status = UserStatus.ACTIVE))
                // 테이블이 없는데도 적재해 둔 오버라이드가 그대로 나온다 = DB 를 만지지 않았다.
                assertThat(creditPolicyService.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(700)
                user.id
            }
        } finally {
            jdbcTemplate.execute("ALTER TABLE credit_policies_hidden RENAME TO credit_policies")
        }

        // 커밋이 실제로 됐는지는 트랜잭션 밖에서 다시 읽어 확인한다(rollback-only 였다면 행이 없다).
        assertThat(savedId).isNotNull()
        assertThat(userRepository.findById(savedId!!)).isPresent()
    }

    @Test
    fun `정책 적재가 실패해도 애플리케이션은 기본값으로 계속 동작한다`() {
        // 부팅 선적재·주기 갱신이 실패하는 상황. 예외가 밖으로 나오면 스케줄러가 영구 중단되고 부팅도 위험하다.
        jdbcTemplate.execute("ALTER TABLE credit_policies RENAME TO credit_policies_hidden")
        try {
            creditPolicyService.refresh()

            assertThat(creditPolicyService.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(350)
        } finally {
            jdbcTemplate.execute("ALTER TABLE credit_policies_hidden RENAME TO credit_policies")
        }
    }
}
