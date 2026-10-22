package com.knk.manyak.admin

import com.knk.manyak.admin.repository.AdminChangeActorRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 앱 datasource와 별개인 PostgreSQL에서 실제 NOWAIT와 커밋까지의 잠금 보존을 확인한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminChangeActorPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "actor-guard-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private val pool = Executors.newFixedThreadPool(2)
    private lateinit var jdbc: JdbcTemplate
    private lateinit var tx: TransactionTemplate
    private lateinit var actors: AdminChangeActorRepository

    @BeforeAll fun start() {
        pg.start()
        val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "actor-guard-test")
        jdbc = JdbcTemplate(ds)
        tx = TransactionTemplate(DataSourceTransactionManager(ds))
        actors = AdminChangeActorRepository(jdbc)
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, status VARCHAR(20) NOT NULL, nickname TEXT NOT NULL)")
        jdbc.execute("CREATE TABLE admin_accounts(user_id BIGINT PRIMARY KEY REFERENCES users(id))")
    }
    @BeforeEach fun seed() {
        jdbc.execute("TRUNCATE admin_accounts, users")
        (1L..2L).forEach { id ->
            jdbc.update("INSERT INTO users VALUES (?, 'ACTIVE', ?)", id, "user-$id")
            jdbc.update("INSERT INTO admin_accounts VALUES (?)", id)
        }
    }
    @AfterAll fun stop() { pool.shutdownNow(); pg.stop() }

    @Test fun `처리자나 역할 잠금이 경합하면 기다리지 않아 교차 잠금 순서가 추가되지 않는다`() {
        for (sql in listOf("SELECT id FROM users WHERE id = 1 FOR UPDATE", "SELECT user_id FROM admin_accounts WHERE user_id = 1 FOR UPDATE")) {
            tx.executeWithoutResult {
                jdbc.queryForList(sql)
                val attempt = pool.submit(Callable { tx.execute { actors.lockActiveAdmin(1) } })
                assertThatThrownBy { attempt.get(3, TimeUnit.SECONDS) }
                    .hasCauseInstanceOf(PessimisticLockingFailureException::class.java)
            }
            assertThat(tx.execute { actors.lockActiveAdmin(1) }).isTrue()
        }
    }

    @Test fun `재검증 잠금은 커밋까지 보존되어 탈퇴와 역할 회수가 앞지르지 못한다`() {
        tx.executeWithoutResult {
            assertThat(actors.lockActiveAdmin(1)).isTrue()
            for (sql in listOf("SELECT id FROM users WHERE id = 1 FOR UPDATE NOWAIT",
                "SELECT user_id FROM admin_accounts WHERE user_id = 1 FOR UPDATE NOWAIT")) {
                pool.submit(Callable {
                    assertThatThrownBy { tx.executeWithoutResult { jdbc.queryForList(sql) } }
                        .rootCause().isInstanceOfSatisfying(java.sql.SQLException::class.java) { exception ->
                            assertThat(exception.sqlState).isEqualTo("55P03")
                        }
                }).get(3, TimeUnit.SECONDS)
            }
        }
        assertThat(jdbc.update("UPDATE users SET status = 'DELETED' WHERE id = 1")).isEqualTo(1)
        assertThat(jdbc.update("DELETE FROM admin_accounts WHERE user_id = 1")).isEqualTo(1)
        assertThat(tx.execute { actors.lockActiveAdmin(1) }).isFalse()
    }

    @Test fun `서로를 대상으로 변경해도 처리자 잠금에서 교착 없이 실패한 변경을 롤백한다`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val attempts = (1L..2L).associateWith { actor -> pool.submit(Callable {
            try {
                tx.executeWithoutResult {
                    jdbc.update("UPDATE users SET nickname = ? WHERE id = ?", "changed-by-$actor", 3L - actor)
                    ready.countDown()
                    check(start.await(3, TimeUnit.SECONDS))
                    check(actors.lockActiveAdmin(actor))
                }
                true
            } catch (_: PessimisticLockingFailureException) { false }
        }) }
        try {
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue()
        } finally { start.countDown() }
        val outcomes = attempts.mapValues { it.value.get(5, TimeUnit.SECONDS) }
        assertThat(outcomes.values).contains(false)
        outcomes.forEach { (actor, committed) ->
            val target = 3L - actor
            assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String::class.java, target))
                .isEqualTo(if (committed) "changed-by-$actor" else "user-$target")
        }
    }
}
