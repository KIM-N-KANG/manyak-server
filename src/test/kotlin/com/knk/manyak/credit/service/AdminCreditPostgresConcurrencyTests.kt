package com.knk.manyak.credit.service

import com.knk.manyak.admin.repository.AdminAuditLogRepository
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.dto.*
import com.knk.manyak.credit.repository.*
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.slf4j.MDC
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = [CreditPolicyRepository::class, UserRepository::class, AdminAuditLogRepository::class])
class AdminCreditPostgresConfig

/** 전용 PostgreSQL에만 연결하는 작은 Spring/JPA 컨텍스트. 앱 테스트의 H2/Flyway 격리 장치는 바꾸지 않는다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminCreditPostgresConcurrencyTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "credit-lock-test")
        .withEnv("POSTGRES_DB", "credit_lock_test").withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var jdbc: JdbcTemplate
    private lateinit var tx: TransactionTemplate
    private lateinit var policy: AdminCreditPolicyService
    private lateinit var adjustment: AdminCreditAdjustmentService
    private lateinit var locks: CreditAdminLocks
    private lateinit var admin: User
    private lateinit var member: User
    private val mapper = jacksonObjectMapper()

    @BeforeAll fun start() {
        pg.start()
        val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/credit_lock_test", "postgres", "credit-lock-test")
        Flyway.configure().dataSource(ds).locations("filesystem:src/main/resources/db/migration").load().migrate()
        jdbc = JdbcTemplate(ds)
        context = AnnotationConfigApplicationContext()
        context.register(AdminCreditPostgresConfig::class.java)
        context.registerBean("dataSource", javax.sql.DataSource::class.java, Supplier { ds })
        context.registerBean(JdbcTemplate::class.java, Supplier { jdbc })
        context.registerBean(tools.jackson.databind.ObjectMapper::class.java, Supplier { mapper })
        context.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean::class.java, Supplier {
            LocalContainerEntityManagerFactoryBean().apply {
                dataSource = ds
                setPackagesToScan("com.knk.manyak")
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none", "hibernate.jdbc.time_zone" to "UTC"))
            }
        })
        context.registerBean("transactionManager", PlatformTransactionManager::class.java, Supplier {
            JpaTransactionManager(context.getBean(EntityManagerFactory::class.java)).apply { dataSource = ds }
        })
        context.registerBean(CreditPolicyService::class.java, Supplier {
            CreditPolicyService(context.getBean(CreditPolicyRepository::class.java), 1000, 2000, 10, 250, 250, 20)
        })
        context.register(PostgresCreditAdminLocks::class.java, AdminAuditService::class.java, AdminCreditPolicyService::class.java,
            AdminCreditAdjustmentService::class.java, CreditWalletService::class.java, CreditWalletProvisioner::class.java)
        context.refresh()
        tx = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
        policy = context.getBean(AdminCreditPolicyService::class.java)
        adjustment = context.getBean(AdminCreditAdjustmentService::class.java)
        locks = context.getBean(CreditAdminLocks::class.java)
    }
    @AfterAll fun stop() { if (::context.isInitialized) context.close(); pg.stop() }
    @BeforeEach fun clean() {
        jdbc.execute("TRUNCATE admin_audit_logs, credit_admin_adjustments, credit_lots, credit_transactions, credit_wallets, credit_policies, users RESTART IDENTITY CASCADE")
        val users = context.getBean(UserRepository::class.java)
        admin = users.save(User(nickname = "PG 관리자"))
        member = users.save(User(nickname = "PG 대상"))
    }
    private fun <T> request(block: () -> T): T {
        MDC.put("request_id", "pg_credit_concurrency")
        return try { block() } finally { MDC.clear() }
    }
    private fun put(amount: Long) { policy.upsert(admin.id, "attendance_reward", AdminCreditPolicyRequest(amount, reason = "PG 동시 변경")) }
    private fun grant(key: String = "pg-first-grant") = adjustment.adjust(admin.id,
        AdminCreditAdjustmentRequest(member.publicId, CreditAdjustmentType.GRANT, 100, idempotencyKey = key, reason = "PG 잠금 검증"))

    private fun waitForLock(pid: Int, queryFragment: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var blocked = false
        while (System.nanoTime() < deadline) {
            blocked = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock' AND query LIKE ?)",
                Boolean::class.java, pid, "%$queryFragment%") == true
            if (blocked) break
            Thread.sleep(10)
        }
        assertThat(blocked).describedAs("두 번째 트랜잭션은 실제 PG의 %s 잠금에서 대기한다", queryFragment).isTrue()
    }
    private fun overlap(first: () -> Unit, second: () -> Unit, fragment: String = "pg_advisory_xact_lock", expectedStatus: Int? = null) {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val pid = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { request { tx.executeWithoutResult {
                first(); held.countDown(); check(release.await(15, TimeUnit.SECONDS))
            } } })
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue()
            val b = pool.submit(Callable { request { tx.executeWithoutResult {
                pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                secondStarted.countDown(); second()
            } } })
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue()
            waitForLock(pid.get(), fragment)
            release.countDown()
            a.get(10, TimeUnit.SECONDS)
            if (expectedStatus == null) b.get(10, TimeUnit.SECONDS) else {
                val failure = assertThrows<java.util.concurrent.ExecutionException> { b.get(10, TimeUnit.SECONDS) }
                assertThat((failure.cause as ResponseStatusException).statusCode.value()).isEqualTo(expectedStatus)
            }
        } finally { release.countDown(); pool.shutdownNow() }
    }
    private fun states() = jdbc.query("SELECT before_state::text, after_state::text FROM admin_audit_logs ORDER BY id",
        { rs, _ -> rs.getString(1)?.let(mapper::readTree) to rs.getString(2)?.let(mapper::readTree) })

    @Test fun `키 잠금은 트랜잭션 밖에서 거절된다`() {
        assertThatThrownBy { locks.policy(CreditPolicyKey.ATTENDANCE_REWARD) }
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException::class.java)
    }
    @Test fun `PG 동시 최초 생성은 키 잠금 뒤 before를 읽고 두 요청 모두 성공한다`() {
        overlap({ put(300) }, { put(500) })
        val states = states()
        assertThat(states).hasSize(2)
        assertThat(states[0].first).isNull()
        assertThat(states[1].first!!["amount"].asLong()).isEqualTo(300)
        assertThat(jdbc.queryForObject("SELECT amount FROM credit_policies", Long::class.java)).isEqualTo(500)
        // 조회의 readOnly 트랜잭션에서도 7종의 advisory 잠금이 허용되고 저장된 행을 읽는다.
        val listed = policy.list()
        assertThat(listed.items).hasSize(7)
        assertThat(listed.items.single { it.policyKey == "attendance_reward" }.override!!.amount).isEqualTo(500)
    }
    @Test fun `PG 두 변경의 감사 before는 실제 직전 커밋 값이다`() {
        jdbc.update("INSERT INTO credit_policies(policy_key,amount,updated_at) VALUES ('attendance_reward',250,now())")
        overlap({ put(300) }, { put(500) })
        assertThat(states().map { it.first!!["amount"].asLong() }).containsExactly(250L, 300L)
    }
    @Test fun `PG 변경과 삭제의 before도 같은 키 잠금으로 연결된다`() {
        jdbc.update("INSERT INTO credit_policies(policy_key,amount,updated_at) VALUES ('attendance_reward',250,now())")
        overlap({ put(300) }, { policy.delete(admin.id, "attendance_reward", "PG 삭제") })
        assertThat(states()[1].first!!["amount"].asLong()).isEqualTo(300)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_policies", Long::class.java)).isZero()
    }
    @Test fun `PG 사용자 잠금 보유 중 첫 지갑 독립 생성이 FK 자기 대기 없이 끝난다`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit(Callable { request { grant() } }).get(5, TimeUnit.SECONDS)
            assertThat(result.balance).isEqualTo(100)
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_wallets", Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_admin_adjustments", Long::class.java)).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }
    @Test fun `PG 지급이 먼저 잠그면 탈퇴는 적용과 감사 커밋까지 기다린다`() {
        overlap({ grant() }, {
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long::class.java, member.id)
            jdbc.update("UPDATE users SET status='DELETED' WHERE id=?", member.id)
        }, "FOR UPDATE")
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id=?", String::class.java, member.id)).isEqualTo("DELETED")
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallets WHERE user_id=?", Long::class.java, member.id)).isEqualTo(100)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isEqualTo(1)
    }

    @Test fun `PG 탈퇴 선점 뒤 지급은 사용자 잠금에서 기다렸다가 최신 상태로 거절된다`() {
        overlap({
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long::class.java, member.id)
            jdbc.update("UPDATE users SET status='DELETED' WHERE id=?", member.id)
        }, {
            grant()
        }, "FOR NO KEY UPDATE", expectedStatus = 409)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_admin_adjustments", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions", Long::class.java)).isZero()
    }
}
