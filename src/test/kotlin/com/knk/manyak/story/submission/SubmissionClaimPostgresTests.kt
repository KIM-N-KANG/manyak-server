package com.knk.manyak.story.submission

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.*
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** 앱 테스트 DB 설정과 별개인 일회용 PostgreSQL에서 실제 SKIP LOCKED를 검증한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmissionClaimPostgresTests {
    private val pg = GenericContainer("postgres:17-alpine").withEnv("POSTGRES_PASSWORD", "claim-test")
        .withEnv("POSTGRES_DB", "claims").withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var tx: TransactionTemplate
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var store: SubmissionClaimStore
    private val now = Instant.parse("2026-09-27T00:00:00Z")
    private val lease = Duration.ofSeconds(300)
    @BeforeAll fun start() {
        pg.start()
        val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/claims", "postgres", "claim-test")
        jdbc = JdbcTemplate(ds)
        // FK 부모만 최소 구성한다. 제출본 테이블은 수정된 V87 원문을 적용한다.
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY); CREATE TABLE stories(id BIGINT PRIMARY KEY)")
        jdbc.execute(java.io.File("src/main/resources/db/migration/V87__create_story_submissions.sql").readText())
        jdbc.execute("INSERT INTO users VALUES (1)")
        val manager = DataSourceTransactionManager(ds)
        tx = TransactionTemplate(manager)
        context = AnnotationConfigApplicationContext()
        context.register(com.knk.manyak.push.outbox.OutboxTransactionTestConfig::class.java)
        context.registerBean("transactionManager", DataSourceTransactionManager::class.java, Supplier { manager })
        context.registerBean(JdbcTemplate::class.java, Supplier { jdbc })
        context.register(SubmissionClaimStore::class.java)
        context.refresh()
        store = context.getBean(SubmissionClaimStore::class.java)
    }
    @AfterAll fun stop() { if (::context.isInitialized) context.close(); pg.stop() }
    @BeforeEach fun clear() { jdbc.update("DELETE FROM story_submissions") }
    private fun insert(): Long = jdbc.queryForObject("""INSERT INTO story_submissions
        (public_id,user_id,kind,payload,input_form,status) VALUES (?,1,'CREATE','{}','{}','PENDING') RETURNING id""", Long::class.java, UUID.randomUUID())!!

    @Test fun `임대 만료와 반환은 attempt 조건으로 이전 실행을 차단한다`() {
        val id = insert()
        assertNull(jdbc.queryForMap("SELECT dispatched_at FROM story_submissions WHERE id = ?", id)["dispatched_at"])
        val first = store.claim(now, lease, 1).single()
        assertEquals(2, first.attempt)
        assertTrue(store.claim(now.plusSeconds(299), lease, 1).isEmpty())
        val next = store.claim(now.plusSeconds(300), lease, 1).single()
        assertEquals(3, next.attempt)
        store.release(first)
        assertTrue(store.claim(now.plusSeconds(301), lease, 1).isEmpty())
        store.release(next)
        assertEquals(4, store.claim(now.plusSeconds(301), lease, 1).single().attempt)
    }
    @Test fun `다른 인스턴스가 잠근 행은 기다리지 않고 다음 행만 선점한다`() {
        val lockedId = insert()
        val nextId = insert()
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val blocker = pool.submit {
                tx.executeWithoutResult {
                    jdbc.queryForObject("SELECT id FROM story_submissions WHERE id = ? FOR UPDATE", Long::class.java, lockedId)
                    acquired.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            assertTimeoutPreemptively(Duration.ofSeconds(3)) {
                assertEquals(listOf(nextId), store.claim(now, lease, 2).map { it.id })
            }
            release.countDown()
            blocker.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(lockedId), store.claim(now, lease, 2).map { it.id })
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
