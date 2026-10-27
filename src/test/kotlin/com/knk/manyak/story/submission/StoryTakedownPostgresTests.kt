package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.outbox.PushOutboxRelay
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryTakedownReason
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

/** 기본 H2 격리 설정은 유지하고, 이 컨텍스트만 일회용 PostgreSQL과 실제 서비스/JPA 잠금을 사용한다. */
@SpringBootTest(properties = ["spring.jpa.hibernate.ddl-auto=validate", "manyak.push.mode=remote"])
@Import(StoryTakedownPostgresConfig::class)
class StoryTakedownPostgresTests {
    class RecordedEvents {
        val notifications = CopyOnWriteArrayList<StoryTakenDown>()
        val records = CopyOnWriteArrayList<StoryTakenDownRecorded>()
        @EventListener fun notification(event: StoryTakenDown) { notifications.add(event) }
        @EventListener fun record(event: StoryTakenDownRecorded) { records.add(event) }
    }
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var submissions: StorySubmissionRepository
    @Autowired lateinit var takedowns: StoryTakedownService
    @Autowired lateinit var updates: StorySubmissionService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var manager: PlatformTransactionManager
    @Autowired lateinit var events: RecordedEvents
    @MockitoBean lateinit var relay: PushOutboxRelay
    private lateinit var admin: User
    private lateinit var owner: User
    private lateinit var story: Story

    @BeforeEach fun setup() {
        jdbc.dataSource!!.connection.use { assertThat(it.metaData.databaseProductName).isEqualTo("PostgreSQL") }
        jdbc.execute("TRUNCATE users, push_outbox RESTART IDENTITY CASCADE")
        events.notifications.clear()
        events.records.clear()
        admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        owner = users.save(User(nickname = "작가", servicePushEnabled = false))
        story = stories.save(Story(userId = owner.id, title = "잠금 검증", visibility = StoryVisibility.PRIVATE))
    }

    @ParameterizedTest @ValueSource(booleans = [true, false])
    fun `공개 중단과 소유자 공개 전환은 어느 선행 순서에서도 교차 대기 없이 완료된다`(takedownFirst: Boolean) {
        val firstFinished = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        fun take() = try {
            org.slf4j.MDC.put("request_id", "postgres-takedown")
            org.slf4j.MDC.put("session_id", "postgres-session")
            takedowns.takeDown(admin.id, story.publicId, StoryTakedownRequest(StoryTakedownReason.ETC, "입력 사유", "내부 메모"))
        } finally { org.slf4j.MDC.clear() }
        fun publish() = updates.update(story.publicId.toString(), UpdateStoryRequest(visibility = StoryVisibility.PUBLIC), owner.id)
        try {
            val first = pool.submit<Any> {
                TransactionTemplate(manager).execute {
                    jdbc.execute("SET LOCAL lock_timeout = '5s'")
                    val result = if (takedownFirst) take() else publish()
                    firstFinished.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    result
                }!!
            }
            if (!firstFinished.await(10, TimeUnit.SECONDS)) first.get(1, TimeUnit.SECONDS)
            val second = pool.submit<Any> {
                secondStarted.countDown()
                TransactionTemplate(manager).execute {
                    jdbc.execute("SET LOCAL lock_timeout = '5s'")
                    if (takedownFirst) publish() else take()
                }!!
            }
            check(secondStarted.await(5, TimeUnit.SECONDS))
            assertThatThrownBy { second.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            // 두 번째 트랜잭션이 PostgreSQL 행 잠금을 실제로 기다리는지 확인한다.
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Long::class.java)).isEqualTo(1)
            }
            release.countDown()
            val firstResult = first.get(15, TimeUnit.SECONDS)
            val secondResult = second.get(15, TimeUnit.SECONDS)
            if (takedownFirst) assertThat(secondResult).isInstanceOf(SubmissionAccepted::class.java)
            else assertThat(firstResult).isNotInstanceOf(SubmissionAccepted::class.java)
        } finally { release.countDown(); pool.shutdownNow() }

        val saved = stories.findById(story.id).orElseThrow()
        assertThat(saved.visibility).isEqualTo(StoryVisibility.PRIVATE)
        assertThat(saved.takenDownAt).isNotNull()
        assertThat(saved.takedownReason).isEqualTo(StoryTakedownReason.ETC)
        assertThat(saved.takedownReasonDetail).isEqualTo("입력 사유")
        val rows = submissions.findAll()
        assertThat(rows).hasSize(if (takedownFirst) 1 else 0)
        if (takedownFirst) {
            assertThat(rows.single().status).isEqualTo(SubmissionStatus.PENDING)
            assertThat(rows.single().storyId).isEqualTo(story.id)
            assertThat(rows.single().kind).isEqualTo(SubmissionKind.UPDATE)
        }
        for (table in listOf("admin_audit_logs", "user_notifications", "push_outbox")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)).describedAs(table).isEqualTo(1)
        }
        assertThat(events.notifications).hasSize(1)
        assertThat(events.notifications.single().storyId).isEqualTo(story.publicId)
        assertThat(events.records).containsExactly(StoryTakenDownRecorded(story.publicId, StoryTakedownReason.ETC,
            if (takedownFirst) StoryVisibility.PRIVATE else StoryVisibility.PUBLIC, null, true))
    }
}

/** 실제 PostgreSQL 서비스 통합 검증에서 공유할 수 있는 격리 datasource 설정. */
@TestConfiguration(proxyBeanMethods = false)
class StoryTakedownPostgresConfig {
    @Bean(destroyMethod = "stop")
    fun postgres(): GenericContainer<*> = GenericContainer("postgres:17-alpine")
        .withEnv("POSTGRES_PASSWORD", "takedown-test").withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
        .apply { start() }

    @Bean
    fun dataSource(postgres: GenericContainer<*>): DataSource {
        val ds = DriverManagerDataSource("jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/postgres", "postgres", "takedown-test")
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        return ds
    }
    @Bean fun recordedEvents() = StoryTakedownPostgresTests.RecordedEvents()
}
