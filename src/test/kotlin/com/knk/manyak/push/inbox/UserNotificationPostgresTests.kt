package com.knk.manyak.push.inbox

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** H2가 검증하지 않는 V111 제약·인덱스·동시 충돌을 격리된 실제 PostgreSQL에서 검증한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserNotificationPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "inbox-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var store: UserNotificationStore
    private lateinit var tx: TransactionTemplate
    private val now = Instant.parse("2026-10-24T00:00:00Z")

    @BeforeAll fun start() {
        pg.start()
        val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "inbox-test")
        jdbc = JdbcTemplate(ds)
        tx = TransactionTemplate(DataSourceTransactionManager(ds))
        jdbc.execute("""CREATE TABLE users(id BIGINT PRIMARY KEY, public_id UUID NOT NULL, status TEXT NOT NULL,
            marketing_push_agreed_at TIMESTAMPTZ, reward_identity_user_id BIGINT);
            CREATE TABLE credit_transactions(idempotency_key TEXT);
            CREATE TABLE notices(id BIGINT PRIMARY KEY);
            CREATE TABLE push_campaigns(id BIGINT PRIMARY KEY, notice_id BIGINT UNIQUE REFERENCES notices(id));
            INSERT INTO notices VALUES (1);
            INSERT INTO push_campaigns(id, notice_id) VALUES (1, 1), (2, NULL)""")
        jdbc.execute(File("src/main/resources/db/migration/V111__create_user_notifications.sql").readText())
        jdbc.execute(File("src/main/resources/db/migration/V86__create_push_outbox.sql").readText())
        jdbc.execute(File("src/main/resources/db/migration/V114__credit_expiry_notification_type.sql").readText())
        store = UserNotificationStore(jdbc)
    }
    @AfterAll fun stop() { pg.stop() }
    @BeforeEach fun clean() {
        jdbc.update("DELETE FROM users")
        jdbc.update("DELETE FROM credit_transactions")
        jdbc.update("DELETE FROM push_outbox")
        jdbc.update("INSERT INTO users VALUES (1, ?, 'ACTIVE', ?, NULL)", UUID.randomUUID(), java.sql.Timestamp.from(now))
    }

    @Test fun `V114는 이프 안내 ACTIVITY를 허용하고 모르는 type은 계속 거부한다`() {
        tx.executeWithoutResult {
            store.insert(UserNotification(userId = 1, messageKey = "credit-expiry:member:2026-10-25",
                type = NotificationType.CREDIT_EXPIRY_REMINDER, title = "이프 만료 예정 안내", body = "곧 만료되는 이프가 있어요"))
        }
        assertThat(jdbc.queryForObject("SELECT type FROM user_notifications", String::class.java)).isEqualTo("CREDIT_EXPIRY_REMINDER")
        assertThat(jdbc.queryForObject("SELECT category FROM user_notifications", String::class.java)).isEqualTo("ACTIVITY")
        org.assertj.core.api.Assertions.assertThatThrownBy {
            jdbc.update("UPDATE user_notifications SET type='UNKNOWN'")
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    @Test fun `태그 없던 기존 공지 캠페인은 V111 후 연결 공지 태그를 갖는다`() {
        assertThat(jdbc.queryForObject("SELECT notice_tag FROM push_campaigns WHERE id=1", String::class.java))
            .isEqualTo(jdbc.queryForObject("SELECT tag FROM notices WHERE id=1", String::class.java))
            .isEqualTo("SERVICE")
        assertThat(jdbc.queryForObject("SELECT notice_tag FROM push_campaigns WHERE id=2", String::class.java)).isNull()
    }

    @Test fun `완료 기록과 초안 재요청은 사용자부터 잠가 PG 데드락 없이 함께 끝난다`() {
        // 실제 updateStatus를 실행하되 저장소 경계를 같은 PG 연결의 SELECT FOR UPDATE로 연결한다.
        // 재요청은 resolveExistingLocked와 같은 users → 생성 요청 잠금 순서로 겹친다.
        jdbc.execute("CREATE TABLE story_creation_requests(id BIGINT PRIMARY KEY, user_id BIGINT, status TEXT, result_json TEXT)")
        jdbc.update("INSERT INTO story_creation_requests VALUES (42,1,'PENDING',NULL)")
        val userHeld = java.util.concurrent.CountDownLatch(1)
        val completionAttempt = java.util.concurrent.CountDownLatch(1)
        val replayAttempt = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val requestId = UUID.randomUUID()
        val row = com.knk.manyak.story.entity.StoryCreationRequest(id = 42, requestId = requestId,
            userId = 1, stage = com.knk.manyak.story.entity.StoryCreationStage.STORY_COMPLETION)
        val users = org.mockito.Mockito.mock(com.knk.manyak.auth.repository.UserRepository::class.java) { call ->
            if (call.method.name == "findByIdForUpdate") {
                completionAttempt.countDown()
                jdbc.execute("SET LOCAL statement_timeout = '5s'")
                jdbc.queryForObject("SELECT id FROM users WHERE id=1 FOR UPDATE", Long::class.java)
                com.knk.manyak.auth.entity.User(id = 1, nickname = "잠금")
            } else org.mockito.Answers.RETURNS_DEFAULTS.answer(call)
        }
        val repository = org.mockito.Mockito.mock(com.knk.manyak.story.repository.StoryCreationRequestRepository::class.java) { call ->
            when (call.method.name) {
                "findUserIdById" -> jdbc.queryForObject("SELECT user_id FROM story_creation_requests WHERE id=42", Long::class.java)
                "findByIdForUpdate" -> {
                    jdbc.execute("SET LOCAL statement_timeout = '5s'")
                    jdbc.queryForObject("SELECT id FROM story_creation_requests WHERE id=42 FOR UPDATE", Long::class.java)
                    completionAttempt.countDown()
                    check(replayAttempt.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    row
                }
                "save" -> {
                    jdbc.update("UPDATE story_creation_requests SET status=?,result_json=? WHERE id=42", row.status.name, row.resultJson)
                    row
                }
                else -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        val recorder = com.knk.manyak.story.service.StoryCreationRequestRecorder(repository,
            tools.jackson.module.kotlin.jacksonObjectMapper(),
            org.mockito.Mockito.mock(com.knk.manyak.user.draft.StoryDraftRepository::class.java), users, 300,
            DataSourceTransactionManager(jdbc.dataSource!!))
        try {
            val replay = pool.submit {
                tx.executeWithoutResult {
                    jdbc.execute("SET LOCAL statement_timeout = '5s'")
                    jdbc.queryForObject("SELECT id FROM users WHERE id=1 FOR UPDATE", Long::class.java)
                    userHeld.countDown()
                    check(completionAttempt.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    replayAttempt.countDown()
                    jdbc.queryForObject("SELECT id FROM story_creation_requests WHERE id=42 FOR UPDATE", Long::class.java)
                }
            }
            check(userHeld.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val completion = pool.submit {
                val recordNotification: () -> Unit = {
                    users.findByIdForUpdate(1)
                    store.insert(UserNotification(userId = 1, messageKey = "story-completed:$requestId",
                        type = NotificationType.STORY_COMPLETED, title = "완성", body = "본문"))
                    Unit
                }
                org.springframework.test.util.ReflectionTestUtils.invokeMethod<Unit>(recorder, "updateStatus",
                    42L, com.knk.manyak.story.entity.StoryCreationRequestStatus.COMPLETED, "{}", recordNotification)
            }
            val errors = listOf(completion, replay).mapNotNull { future ->
                runCatching { future.get(15, java.util.concurrent.TimeUnit.SECONDS) }.exceptionOrNull()
            }
            val details = errors.map { error -> generateSequence(error) { it.cause }.joinToString(" -> ") {
                "${it.javaClass.simpleName}:${(it as? java.sql.SQLException)?.sqlState}:${it.message}"
            } }
            assertThat(errors).withFailMessage("동시 완료/재요청 실패: %s", details).isEmpty()
            assertThat(jdbc.queryForObject("SELECT status FROM story_creation_requests WHERE id=42", String::class.java)).isEqualTo("COMPLETED")
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isEqualTo(1)
        } finally {
            completionAttempt.countDown(); replayAttempt.countDown()
            pool.shutdownNow()
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
            jdbc.execute("DROP TABLE story_creation_requests")
        }
    }

    @Test fun `공지 태그 마이그레이션은 기존 행만 SERVICE로 채우고 기본값 없이 다섯 값만 허용한다`() {
        assertThat(jdbc.queryForObject("SELECT tag FROM notices WHERE id=1", String::class.java)).isEqualTo("SERVICE")
        val column = jdbc.queryForMap("SELECT character_maximum_length,is_nullable,column_default FROM information_schema.columns WHERE table_name='notices' AND column_name='tag'")
        assertThat(column["character_maximum_length"]).isEqualTo(16)
        assertThat(column["is_nullable"]).isEqualTo("NO")
        assertThat(column["column_default"]).isNull()
        assertThatThrownBy { jdbc.update("INSERT INTO notices(id) VALUES (2)") }
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        for (table in listOf("notices", "push_campaigns")) {
            val field = if (table == "notices") "tag" else "notice_tag"
            assertThatThrownBy { jdbc.update("INSERT INTO $table(id,$field) VALUES (99,'NOTICE')") }
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
            for ((index, tag) in listOf("SERVICE", "UPDATE", "MAINTENANCE", "EVENT", "PROMOTION").withIndex()) {
                jdbc.update("INSERT INTO $table(id,$field) VALUES (?,?)", index + 10, tag)
            }
        }
        assertThatThrownBy { jdbc.update("""INSERT INTO user_notifications(public_id,user_id,message_key,type,category,title,body,notice_tag)
            VALUES (?,1,'tag','NOTICE','NEWS','','','OTHER')""", UUID.randomUUID()) }
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    @Test fun `동시 중복 삽입은 한 행만 남기고 읽음과 모두 읽음은 최초 시각을 보존한다`() {
        val row = UserNotification(userId = 1, messageKey = "same", type = NotificationType.STORY_COMPLETED,
            title = "원본", body = "본문", createdAt = now)
        val futures = (1..2).map { CompletableFuture.runAsync { tx.executeWithoutResult { store.insert(row) } } }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
        assertThat(store.list(1, null, null, 50)).hasSize(1)
        store.markRead(1, row.publicId, now)
        store.markAllRead(1, null, now.plusSeconds(30))
        assertThat(store.list(1, null, null, 50).single().readAt).isEqualTo(now)
        assertThat(store.unreadCount(1).total).isZero()
    }

    @Test fun `회차의 조회 삽입은 광고와 보상 신원을 따르고 롤백과 재실행에 안전하다`() {
        jdbc.update("INSERT INTO users VALUES (2, ?, 'ACTIVE', NULL, NULL), (3, ?, 'SUSPENDED', ?, NULL), (4, ?, 'ACTIVE', ?, 9)",
            UUID.randomUUID(), UUID.randomUUID(), java.sql.Timestamp.from(now), UUID.randomUUID(), java.sql.Timestamp.from(now))
        jdbc.update("INSERT INTO credit_transactions VALUES ('attendance:9:2026-10-24')")
        val data = NotificationContent(NotificationType.ATTENDANCE_REMINDER, "(광고) 출석", "본문", null)
        tx.executeWithoutResult { status -> store.insertScheduled(data, "attendance", "2026-10-24", now); status.setRollbackOnly() }
        assertThat(store.list(1, null, null, 50)).isEmpty()
        repeat(2) { tx.executeWithoutResult { store.insertScheduled(data, "attendance", "2026-10-24", now) } }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isEqualTo(1)
        assertThat(store.list(1, NotificationCategory.ACTIVITY, null, 50)).hasSize(1)
    }

    @Test fun `마이그레이션의 인덱스와 체크 제약 및 회원 삭제 연쇄를 적용한다`() {
        val indexes = jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'user_notifications'")
            .associate { it["indexname"] to it["indexdef"].toString() }
        assertThat(indexes["idx_user_notifications_user_created"]).contains("(user_id, created_at DESC, id DESC)")
        assertThat(indexes["idx_user_notifications_user_category_created"]).contains("(user_id, category, created_at DESC, id DESC)")
        assertThat(indexes["idx_user_notifications_unread"]).contains("(user_id, category)", "WHERE (read_at IS NULL)")
        assertThatThrownBy { jdbc.update("""INSERT INTO user_notifications(public_id, user_id, message_key, type, category, title, body)
            VALUES (?, 1, 'invalid', 'STORY_COMPLETED', 'ALL', '', '')""", UUID.randomUUID()) }
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        store.insert(UserNotification(userId = 1, messageKey = "valid", type = NotificationType.STORY_COMPLETED,
            title = "제목", body = "본문", storyPublicId = UUID.randomUUID()))
        jdbc.update("DELETE FROM users WHERE id = 1")
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isZero()
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["attendance", "promotion"])
    fun `실제 PostgreSQL 회차는 내역 이후 아웃박스 실패에 함께 롤백한다`(scenario: String) {
        val users = org.mockito.Mockito.mock(com.knk.manyak.auth.repository.UserRepository::class.java)
        val user = com.knk.manyak.auth.entity.User(id = 1, nickname = "원자성")
        org.mockito.Mockito.`when`(users.findById(1)).thenReturn(java.util.Optional.of(user))
        org.mockito.Mockito.`when`(users.findAttendanceReminderTargetIds("2026-10-24")).thenReturn(listOf(1L))
        org.mockito.Mockito.`when`(users.findMarketingPushTargetIds()).thenReturn(listOf(1L))
        val realOutbox = com.knk.manyak.push.outbox.PushOutboxStore(jdbc, tools.jackson.module.kotlin.jacksonObjectMapper())
        val failingOutbox = org.mockito.Mockito.spy(realOutbox)
        org.mockito.Mockito.doAnswer { call ->
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isEqualTo(1)
            call.callRealMethod()
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_outbox", Long::class.java)).isEqualTo(1)
            error("outbox failed after insert")
        }.`when`(failingOutbox).insert(
            org.mockito.Mockito.any(com.knk.manyak.push.outbox.PushMessage::class.java)
                ?: com.knk.manyak.push.outbox.PushMessage("", "", data = emptyMap(), requestId = "", sessionId = ""),
            org.mockito.Mockito.any(Instant::class.java) ?: Instant.EPOCH)
        val manager = DataSourceTransactionManager(jdbc.dataSource!!)
        org.springframework.context.annotation.AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(org.springframework.core.env.MapPropertySource("test", mapOf("manyak.push.web-base-url" to "https://manyak.app")))
            context.register(NotificationTransactionTestConfig::class.java)
            context.registerBean("transactionManager", DataSourceTransactionManager::class.java, java.util.function.Supplier { manager })
            context.registerBean(com.knk.manyak.auth.repository.UserRepository::class.java, java.util.function.Supplier { users })
            context.registerBean(UserNotificationStore::class.java, java.util.function.Supplier { store })
            context.registerBean(com.knk.manyak.push.repository.PushCampaignRepository::class.java, java.util.function.Supplier {
                org.mockito.Mockito.mock(com.knk.manyak.push.repository.PushCampaignRepository::class.java) })
            context.register(ScheduledNotificationWriter::class.java)
            context.refresh()
            val round = context.getBean(ScheduledNotificationWriter::class.java)
            val outbox = com.knk.manyak.push.outbox.ScheduledPushOutboxWriter(users, failingOutbox)
            assertThatThrownBy {
                round.record(mapOf("type" to if (scenario == "attendance") "ATTENDANCE_REMINDER" else "PROMOTION", "title" to "(광고) 제목", "body" to "본문"),
                    scenario, "2026-10-24", outbox)
            }.isInstanceOf(IllegalStateException::class.java).hasMessage("outbox failed after insert")
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_outbox", Long::class.java)).isZero()
    }

}


@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
@org.springframework.transaction.annotation.EnableTransactionManagement
class NotificationTransactionTestConfig
