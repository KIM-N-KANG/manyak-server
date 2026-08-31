package com.knk.manyak.credit.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
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
 * 정책 조회 실패가 **호출자 트랜잭션을 죽이지 않는지** 고정한다(KNK-1056, Codex 리뷰 P2).
 *
 * 크레딧 경로는 `@Transactional` 안에서 정책을 읽는다(출석 적립·초대 제출 등). 정책 조회가 기본 전파로
 * 그 트랜잭션에 참여하면, 조회 실패가 바깥을 rollback-only로 찍어 **예외를 잡아 기본값으로 폴백해도 커밋이
 * 실패**한다. 그러면 "DB 조회가 실패해도 요청을 막지 않는다"는 이 기능의 설계 목표가 성립하지 않는다.
 * [com.knk.manyak.credit.repository.CreditPolicyRepository.findAll]의 `REQUIRES_NEW`가 그걸 막는다.
 *
 * 조회 실패는 mock이 아니라 **실제 SQL 실패**로 만든다: 테이블을 잠깐 딴 이름으로 옮긴다. 그래야 프록시
 * 경계(REQUIRES_NEW가 실제로 새 트랜잭션을 여는지)까지 함께 검증된다 — 자기 호출이나 애노테이션 누락이면
 * 실패가 바깥 트랜잭션에 그대로 번져 커밋이 UnexpectedRollbackException으로 깨진다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CreditPolicyTransactionIsolationIntegrationTest {

    @Autowired private lateinit var creditPolicyService: CreditPolicyService

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun clean() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `트랜잭션 안에서 정책 조회가 실패해도 그 트랜잭션은 정상 커밋된다`() {
        val transactionTemplate = TransactionTemplate(transactionManager)
        // credit_policies 를 잠깐 치워 조회를 실제로 실패시킨다(H2 DDL 은 자동 커밋이라 바깥 트랜잭션 밖에서 한다).
        jdbcTemplate.execute("ALTER TABLE credit_policies RENAME TO credit_policies_hidden")
        val savedId = try {
            transactionTemplate.execute {
                val user = userRepository.save(User(nickname = "정책장애회원", status = UserStatus.ACTIVE))
                // 조회 실패 → warn 로그 + 기본값. 여기서 예외가 나오지 않아야 하고,
                // 바깥 트랜잭션이 rollback-only 로 찍히지도 않아야 한다.
                assertThat(creditPolicyService.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(350)
                user.id
            }
        } finally {
            jdbcTemplate.execute("ALTER TABLE credit_policies_hidden RENAME TO credit_policies")
        }

        // 커밋이 실제로 됐는지는 트랜잭션 밖에서 다시 읽어 확인한다(rollback-only 였다면 행이 없다).
        assertThat(savedId).isNotNull()
        assertThat(userRepository.findById(savedId!!)).isPresent()
    }
}
