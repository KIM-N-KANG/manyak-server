package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditPolicy
import com.knk.manyak.credit.repository.CreditPolicyRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@org.springframework.context.annotation.Import(com.knk.manyak.credit.support.H2CreditAdminLocksConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminCreditConcurrencyIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var manager: PlatformTransactionManager
    @jakarta.persistence.PersistenceContext private lateinit var entityManager: jakarta.persistence.EntityManager
    @MockitoSpyBean private lateinit var policies: CreditPolicyRepository
    private lateinit var admin: User
    private lateinit var member: User
    private lateinit var token: String
    private val mapper = ObjectMapper()
    private val root = "/api/v1/admin/credits"
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = users.save(User(nickname = "관리자"))
        member = users.save(User(nickname = "조정 대상"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }
    private fun put(amount: Long) = client.put().uri("$root/policies/attendance_reward").header("Authorization", "Bearer $token")
        .contentType(MediaType.APPLICATION_JSON).body(mapOf("amount" to amount, "reason" to "동시 변경"))
        .exchange().expectBody(String::class.java).returnResult().status.value()
    private fun delete() = client.method(org.springframework.http.HttpMethod.DELETE).uri("$root/policies/attendance_reward")
        .header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).body(mapOf("reason" to "동시 삭제"))
        .exchange().expectBody(String::class.java).returnResult().status.value()
    private fun adjust(key: String = "withdraw-race") = client.post().uri("$root/adjustments").header("Authorization", "Bearer $token")
        .contentType(MediaType.APPLICATION_JSON).body(mapOf("memberId" to member.publicId.toString(), "type" to "GRANT", "amount" to 100,
            "idempotencyKey" to key, "reason" to "탈퇴 경쟁 검증"))
        .exchange().expectBody(String::class.java).returnResult()

    private fun race(second: () -> Int, initial: Long?): List<Map<String, Any?>> {
        initial?.let { policies.save(CreditPolicy("attendance_reward", it)) }
        val firstRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondRead = CountDownLatch(1)
        val reads = AtomicInteger()
        doAnswer { call ->
            val result = java.util.Optional.ofNullable(entityManager.find(CreditPolicy::class.java, call.getArgument<String>(0)))
            if (call.getArgument<String>(0) == "attendance_reward") {
                if (reads.incrementAndGet() == 1) {
                    firstRead.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                } else secondRead.countDown()
            }
            result
        }.`when`(policies).findById(anyString())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit(Callable { put(300) })
            assertThat(firstRead.await(5, TimeUnit.SECONDS)).isTrue()
            val started = CountDownLatch(1)
            val other = pool.submit(Callable { started.countDown(); second() })
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
            // 첫 요청이 before를 읽은 뒤 커밋하기 전에는 두 번째 요청이 before를 읽지 못한다.
            val leakedRead = secondRead.await(300, TimeUnit.MILLISECONDS)
            release.countDown()
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200)
            assertThat(other.get(10, TimeUnit.SECONDS)).isEqualTo(200)
            assertThat(leakedRead).isFalse()
            return jdbc.queryForList("SELECT action, CAST(before_state AS VARCHAR) AS before_state, CAST(after_state AS VARCHAR) AS after_state FROM admin_audit_logs ORDER BY id")
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `같은 키의 두 변경 감사는 직전 값을 순서대로 기록한다`() {
        val rows = race({ put(500) }, 250)
        assertThat(mapper.readTree(rows[0]["before_state"].toString())["amount"].asLong()).isEqualTo(250)
        assertThat(mapper.readTree(rows[1]["before_state"].toString())["amount"].asLong()).isEqualTo(300)
        assertThat(mapper.readTree(rows[1]["after_state"].toString())["amount"].asLong()).isEqualTo(500)
    }

    @Test fun `없는 키의 동시 최초 생성도 두 요청이 성공하고 감사가 연결된다`() {
        val rows = race({ put(500) }, null)
        assertThat(rows[0]["before_state"]).isNull()
        assertThat(mapper.readTree(rows[1]["before_state"].toString())["amount"].asLong()).isEqualTo(300)
        assertThat(policies.count()).isEqualTo(1)
    }

    @Test fun `변경과 삭제가 겹쳐도 삭제 감사는 실제 지운 값을 기록한다`() {
        val rows = race({ delete() }, 250)
        assertThat(rows[1]["action"]).isEqualTo("CREDIT_POLICY_DELETED")
        assertThat(mapper.readTree(rows[1]["before_state"].toString())["amount"].asLong()).isEqualTo(300)
        assertThat(policies.count()).isZero()
    }

    @Test fun `탈퇴가 먼저 사용자 잠금을 잡으면 지급은 최신 탈퇴 상태를 보고 거절한다`() {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val withdrawal = pool.submit(Callable { TransactionTemplate(manager).executeWithoutResult {
                val user = users.findByIdForUpdate(member.id)!!
                user.status = UserStatus.DELETED
                locked.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            } })
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue()
            val grant = pool.submit(Callable { adjust() })
            // 탈퇴가 행을 잡은 구간에 요청을 겹친다. 완료 여부는 상태 판정 뒤에 검증한다.
            Thread.sleep(200)
            release.countDown()
            withdrawal.get(10, TimeUnit.SECONDS)
            assertThat(grant.get(10, TimeUnit.SECONDS).status.value()).isEqualTo(409)
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_admin_adjustments", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions", Long::class.java)).isZero()
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `저장된 결과 재응답은 탈퇴 뒤에도 대상 판정보다 먼저 처리한다`() {
        val first = adjust("completed-before-withdrawal")
        assertThat(first.status.value()).isEqualTo(200)
        jdbc.update("UPDATE users SET status='DELETED' WHERE id=?", member.id)
        val duplicate = adjust("completed-before-withdrawal")
        assertThat(duplicate.status.value()).isEqualTo(200)
        assertThat(mapper.readTree(duplicate.responseBody!!)["duplicated"].asBoolean()).isTrue()
        assertThat(adjust("new-after-withdrawal").status.value()).isEqualTo(409)
    }
}
