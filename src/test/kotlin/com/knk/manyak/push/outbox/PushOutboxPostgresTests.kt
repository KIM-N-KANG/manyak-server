package com.knk.manyak.push.outbox

import com.knk.manyak.support.SharedPostgres
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.event.StoryCompletedEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.mockito.Mockito.*
import org.slf4j.MDC
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.*
import java.util.*
import java.util.function.Supplier
import java.util.concurrent.*

/** 테스트 전용 컨테이너만 사용한다. 앱의 datasource/Flyway 프로파일 격리는 변경하지 않는다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushOutboxPostgresTests {
    private val now = Instant.parse("2026-09-25T00:00:00Z")
    private val mapper = jacksonObjectMapper()
    private lateinit var jdbc: JdbcTemplate
    /** 저장소가 [jdbc]로 실행한 문장. 문장 수를 보는 테스트만 비우고 읽는다. */
    private val statements: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private lateinit var tx: TransactionTemplate
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var store: PushOutboxStore
    private val user = User(id = 7, nickname = "제작자", servicePushEnabled = false)
    private val event = StoryCompletedEvent(7, UUID.randomUUID(), UUID.randomUUID().toString(), "완성 제목")

    @BeforeAll fun start() {
        val ds = SharedPostgres.database()
        jdbc = object : JdbcTemplate(ds) {
            override fun update(sql: String, vararg args: Any?): Int { statements += sql.trim().substringBefore(' ').lowercase(); return super.update(sql, *args) }
            override fun <T : Any?> query(sql: String, rowMapper: org.springframework.jdbc.core.RowMapper<T>, vararg args: Any?): MutableList<T> {
                statements += sql.trim().substringBefore(' ').lowercase(); return super.query(sql, rowMapper, *args)
            }
        }
        val migration = java.io.File("src/main/resources/db/migration/V86__create_push_outbox.sql").readText()
        jdbc.execute(migration)
        jdbc.execute(java.io.File("src/main/resources/db/migration/V128__push_outbox_trace_context.sql").readText())
        val manager = DataSourceTransactionManager(ds)
        tx = TransactionTemplate(manager)
        context = AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(MapPropertySource("test", mapOf("manyak.push.mode" to "remote")))
        context.register(OutboxTransactionTestConfig::class.java)
        context.registerBean("transactionManager", DataSourceTransactionManager::class.java, Supplier { manager })
        context.registerBean(JdbcTemplate::class.java, Supplier { jdbc })
        context.registerBean(tools.jackson.databind.ObjectMapper::class.java, Supplier { mapper })
        context.registerBean(Clock::class.java, Supplier { Clock.fixed(now, ZoneOffset.UTC) })
        context.registerBean(UserRepository::class.java, Supplier {
            mock(UserRepository::class.java).also {
                `when`(it.findById(7)).thenReturn(Optional.of(user))
                val repository = it
                // 묶음 변환은 테스트가 findById로 등록한 회원만 찾는다.
                doAnswer { call -> call.getArgument<Collection<Long>>(0).mapNotNull { id -> repository.findById(id).orElse(null)?.let { found -> publicIdRow(id, found.publicId) } } }
                    .`when`(it).findPublicIdsByIdIn(anyCollection())
            }
        })
        context.register(PushOutboxStore::class.java, StoryCompletionOutboxListener::class.java, ScheduledPushOutboxWriter::class.java,
            com.knk.manyak.auth.service.AccountQueryService::class.java)
        context.refresh()
        store = context.getBean(PushOutboxStore::class.java)
    }
    @AfterAll fun stop() { if (::context.isInitialized) context.close() }
    @BeforeEach fun clean() { jdbc.update("DELETE FROM push_outbox"); MDC.clear() }
    @AfterEach fun clearMdc() { MDC.clear() }
    private fun record() { tx.executeWithoutResult { context.publishEvent(event) } }
    private fun count() = jdbc.queryForObject("SELECT count(*) FROM push_outbox", Int::class.java)
    private fun message() = mapper.readValue(jdbc.queryForObject("SELECT payload::text FROM push_outbox", String::class.java), PushMessage::class.java)

    @Test fun `출석 회차는 실제 PG에서 원자적으로 기록하며 재실행한 요청은 세지 않는다`() {
        val writer = context.getBean(ScheduledPushOutboxWriter::class.java)
        val expiry = Instant.parse("2026-09-25T15:00:00Z")
        val data = mapOf("type" to "ATTENDANCE_REMINDER", "date" to "2026-09-25")
        assertThat(writer.record(listOf(7), data, "attendance", "2026-09-25", expiry)).isEqualTo(1)
        assertThat(writer.record(listOf(7), data, "attendance", "2026-09-25", expiry)).isZero()
        assertThat(count()).isEqualTo(1)
        val row = store.claim(now, Duration.ofSeconds(60), 100).single()
        assertThat(row.message.expiresAt).isEqualTo(expiry)
        assertThat(row.message.messageId).isEqualTo("attendance:${user.publicId}:2026-09-25")
    }

    @Test fun `캠페인 재실행은 실제 PG에서 기존 회원을 건너뛰고 추가 회원만 기록한다`() {
        val writer = context.getBean(ScheduledPushOutboxWriter::class.java)
        val users = context.getBean(UserRepository::class.java)
        `when`(users.findById(8)).thenReturn(Optional.of(User(id = 8, nickname = "추가 회원")))
        val campaignId = UUID.randomUUID().toString()
        val data = mapOf("type" to "PROMOTION", "campaignId" to campaignId)
        assertThat(writer.record(listOf(7), data, "promotion", campaignId)).isEqualTo(1)
        assertThat(writer.record(listOf(7, 8), data, "promotion", campaignId)).isEqualTo(1)
        assertThat(writer.record(listOf(7, 8), data, "promotion", campaignId)).isZero()
        assertThat(count()).isEqualTo(2)
    }

    @Test fun `회차 중간 실패는 앞서 기록한 요청도 실제 PG에서 롤백한다`() {
        val writer = context.getBean(ScheduledPushOutboxWriter::class.java)
        val data = mapOf("type" to "PROMOTION")
        val campaignId = UUID.randomUUID().toString()
        // 회차의 묶음 기록이 실제로 삽입된 뒤 실패한다. 회차 트랜잭션이 기록한 요청까지 되돌린다.
        val failing = spy(PushOutboxStore(jdbc, mapper))
        var inserts = 0
        doAnswer { call ->
            inserts++
            call.callRealMethod()
            assertThat(count()).isEqualTo(1)
            error("second insert failed")
        }.`when`(failing).insertAll(anyList(), any(Instant::class.java) ?: Instant.EPOCH)
        // 작성기 프록시의 트랜잭션 경계를 그대로 쓰려고 대상 객체의 저장소만 잠시 바꾼다.
        val target = org.springframework.test.util.AopTestUtils.getTargetObject<ScheduledPushOutboxWriter>(writer)
        org.springframework.test.util.ReflectionTestUtils.setField(target, "store", failing)
        try {
            assertThatThrownBy { writer.record(listOf(7, 7), data, "promotion", campaignId) }
                .isInstanceOf(IllegalStateException::class.java).hasMessage("second insert failed")
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(target, "store", store)
        }
        assertThat(inserts).isEqualTo(1)
        assertThat(count()).isZero()
        // 대상 회원이 없으면 기록 전에 같은 예외로 실패하고 아무것도 남지 않는다.
        assertThatThrownBy { writer.record(listOf(7, 999), data, "promotion", campaignId) }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThat(count()).isZero()
        assertThat(writer.record(listOf(7), data, "promotion", campaignId)).isEqualTo(1)
        assertThat(writer.record(listOf(7), data, "promotion", campaignId)).isZero()
        assertThat(message().expiresAt).isNull()
        val payload = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM push_outbox", String::class.java))
        assertThat(payload.has("expiresAt")).isFalse()
    }

    @Test fun `remote 기록은 동의 설정과 무관하며 스키마와 도메인 멱등키를 보존한다`() {
        MDC.put("request_id", "trace-request"); MDC.put("session_id", "trace-session")
        tx.executeWithoutResult {
            context.publishEvent(event)
            assertThat(count()).isEqualTo(1)
        }
        record() // DB의 message_id 유일 제약도 재기록을 방어한다.
        assertThat(count()).isEqualTo(1)
        val payload = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM push_outbox", String::class.java))
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("messageId", "recipientId", "kind", "type", "data", "requestId", "sessionId", "schemaVersion")
        assertThat(message()).isEqualTo(PushMessage("story-completed:${event.requestId}", user.publicId.toString(),
            data = mapOf("type" to "STORY_COMPLETED", "storyId" to event.storyPublicId, "title" to event.title,
                "deepLink" to "https://manyak.app/stories/${event.storyPublicId}?utm_source=push&utm_medium=push&utm_campaign=story_completed&utm_content=story_completed"), requestId = "trace-request", sessionId = "trace-session"))
    }
    @Test fun `완성 트랜잭션 롤백은 아웃박스도 롤백한다`() {
        tx.executeWithoutResult { status -> context.publishEvent(event); status.setRollbackOnly() }
        assertThat(count()).isZero()
        assertThatThrownBy { context.publishEvent(event) }.isInstanceOf(org.springframework.transaction.IllegalTransactionStateException::class.java)
    }
    @Test fun `local 모드에는 기록 리스너가 없고 행도 생기지 않는다`() {
        AnnotationConfigApplicationContext().use { local ->
            local.environment.propertySources.addFirst(MapPropertySource("test", mapOf("manyak.push.mode" to "local")))
            local.register(StoryCompletionOutboxListener::class.java, PushOutboxStore::class.java, PushOutboxRelay::class.java, KafkaPushConfig::class.java)
            local.refresh()
            tx.executeWithoutResult { local.publishEvent(event) }
            assertThat(local.getBeansOfType(StoryCompletionOutboxListener::class.java)).isEmpty()
            assertThat(local.getBeansOfType(PushPublisher::class.java)).isEmpty()
            assertThat(count()).isZero()
        }
    }
    @Test fun `선점은 임대를 커밋하고 만료 전 재선점을 막는다`() {
        record()
        val rows = store.claim(now, Duration.ofSeconds(60), 100)
        assertThat(rows).hasSize(1)
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM push_outbox", java.sql.Timestamp::class.java)!!.toInstant()).isEqualTo(now.plusSeconds(60))
        assertThat(store.claim(now.plusSeconds(59), Duration.ofSeconds(60), 100)).isEmpty()
        val reclaimed = store.claim(now.plusSeconds(60), Duration.ofSeconds(60), 100).single()
        assertThat(store.complete(rows.single(), now.plusSeconds(61))).isZero()
        assertThat(store.complete(reclaimed, now.plusSeconds(61))).isEqualTo(1)
    }
    @Test fun `선점은 id 순서로 배치 크기만큼 가져오고 가져온 행에만 같은 임대 만료를 기록한다`() {
        tx.executeWithoutResult { (1..3).forEach { store.insert(PushMessage("lease:$it", "r$it", data = mapOf("type" to "NOTICE"), requestId = "t", sessionId = "s"), now) } }
        val first = store.claim(now, Duration.ofSeconds(60), 2)
        assertThat(first.map { it.message.messageId }).containsExactly("lease:1", "lease:2")
        assertThat(first.map { it.id }).isSorted()
        assertThat(first.map { it.leaseUntil }).containsOnly(now.plusSeconds(60))
        assertThat(jdbc.query("SELECT next_attempt_at FROM push_outbox ORDER BY id") { rs, _ -> rs.getTimestamp("next_attempt_at").toInstant() })
            .containsExactly(now.plusSeconds(60), now.plusSeconds(60), now)
        assertThat(store.claim(now, Duration.ofSeconds(60), 2).map { it.message.messageId }).containsExactly("lease:3")
        assertThat(store.claim(now, Duration.ofSeconds(60), 2)).isEmpty()
    }
    @Test fun `스팬 안에서 기록한 행은 메시지마다 기록 스팬의 추적 헤더를 저장하고 선점에서 돌려준다`() {
        var seq = 0
        val traced = PushOutboxStore(jdbc, mapper) {
            seq++
            OutboxTraceHeaders("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b$seq-01", if (seq == 1) "vendor=value" else null)
        }
        tx.executeWithoutResult {
            traced.insert(PushMessage("trace:1", "r1", data = mapOf("type" to "NOTICE"), requestId = "t", sessionId = "s"), now)
            traced.insertAll((2..3).map { PushMessage("trace:$it", "r$it", data = mapOf("type" to "NOTICE"), requestId = "t", sessionId = "s") }, now)
        }
        assertThat(traced.claim(now, Duration.ofSeconds(60), 100).map { it.trace }).containsExactly(
            OutboxTraceHeaders("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b1-01", "vendor=value"),
            OutboxTraceHeaders("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b2-01", null),
            OutboxTraceHeaders("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b3-01", null),
        )
    }
    @Test fun `스팬 밖에서 기록한 행은 추적 헤더 컬럼을 비운다`() {
        tx.executeWithoutResult {
            store.insert(PushMessage("plain:1", "r1", data = mapOf("type" to "NOTICE"), requestId = "t", sessionId = "s"), now)
            store.insertAll(listOf(PushMessage("plain:2", "r2", data = mapOf("type" to "NOTICE"), requestId = "t", sessionId = "s")), now)
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_outbox WHERE trace_parent IS NULL AND trace_state IS NULL", Int::class.java)).isEqualTo(2)
        assertThat(store.claim(now, Duration.ofSeconds(60), 100).map { it.trace }).containsExactly(null, null)
    }
    @Test fun `회차 기록은 대상 순서대로 쌓이고 같은 회차의 중복 대상은 한 건으로 센다`() {
        val writer = context.getBean(ScheduledPushOutboxWriter::class.java)
        val users = context.getBean(UserRepository::class.java)
        val second = User(id = 8, nickname = "두 번째")
        `when`(users.findById(8)).thenReturn(Optional.of(second))
        val data = mapOf("type" to "NOTICE")
        assertThat(writer.record(listOf(8, 7, 8), data, "notice", "camp-order", kind = "SERVICE")).isEqualTo(2)
        assertThat(store.claim(now, Duration.ofSeconds(60), 100).map { it.message.recipientId })
            .containsExactly(second.publicId.toString(), user.publicId.toString())
    }
    @Test fun `회차 기록은 대상 수와 무관하게 INSERT 한 문장이다`() {
        val writer = context.getBean(ScheduledPushOutboxWriter::class.java)
        val users = context.getBean(UserRepository::class.java)
        (8L..9L).forEach { `when`(users.findById(it)).thenReturn(Optional.of(User(id = it, nickname = "대상$it"))) }
        statements.clear()
        assertThat(writer.record(listOf(7, 8, 9), mapOf("type" to "NOTICE"), "notice", "camp-count", kind = "SERVICE")).isEqualTo(3)
        assertThat(statements).containsExactly("insert")
    }
    @Test fun `묶음 기록은 1000건마다 한 문장이고 묶음을 넘는 중복도 세지 않는다`() {
        val messages = (1..1001).map { PushMessage("chunk:${if (it == 1001) 1 else it}", "r$it", data = mapOf("type" to "NOTICE"), requestId = "t", sessionId = "s") }
        statements.clear()
        assertThat(tx.execute { store.insertAll(messages, now) }).isEqualTo(1000)
        assertThat(statements).containsExactly("insert", "insert")
        assertThat(jdbc.queryForList("SELECT message_id FROM push_outbox ORDER BY id", String::class.java))
            .containsExactlyElementsOf((1..1000).map { "chunk:$it" })
        assertThatThrownBy { store.insertAll(messages.take(1), now) }
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException::class.java)
    }
    @Test fun `묶음 기록은 배열 리터럴에서 특수한 문자가 든 payload도 원문 JSON 그대로 저장하고 선점에서 읽는다`() {
        val tricky = "한글 \"따옴표\" 역슬래시\\ {중괄호}, 쉼표\n줄바꿈\t탭 NULL"
        val messages = listOf(
            PushMessage("tricky:1", "r1", data = mapOf("type" to "NOTICE", "title" to tricky, "body" to "{\"a\":1},{b}"), requestId = "t", sessionId = "s"),
            PushMessage("tricky:2,\"{}\\", "r2", data = mapOf("type" to "NOTICE", "title" to "\\\"", "body" to ""), requestId = "t", sessionId = "s"),
        )
        assertThat(tx.execute { store.insertAll(messages, now) }).isEqualTo(2)
        val stored = jdbc.queryForList("SELECT payload::text FROM push_outbox ORDER BY id", String::class.java)
        assertThat(stored.map { mapper.readTree(it) }).containsExactlyElementsOf(messages.map { mapper.readTree(mapper.writeValueAsString(it)) })
        assertThat(store.claim(now, Duration.ofSeconds(60), 100).map { it.message }).containsExactlyElementsOf(messages)
        assertThat(jdbc.queryForList("SELECT message_id FROM push_outbox ORDER BY id", String::class.java))
            .containsExactlyElementsOf(messages.map { it.messageId })
    }
    @Test fun `선점은 행 수와 무관하게 조회 한 문장과 임대 갱신 한 문장이다`() {
        tx.executeWithoutResult { (1..3).forEach { store.insert(PushMessage("count:$it", "r$it", data = mapOf("type" to "NOTICE"), requestId = "t", sessionId = "s"), now) } }
        statements.clear()
        assertThat(store.claim(now, Duration.ofSeconds(60), 100)).hasSize(3)
        assertThat(statements).containsExactly("select", "update")
    }
    @Test fun `다른 트랜잭션이 잠근 행은 SKIP LOCKED로 건너뛴다`() {
        record()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { executor ->
            val holder = executor.submit {
                tx.executeWithoutResult {
                    jdbc.queryForList("SELECT id FROM push_outbox FOR UPDATE")
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            try {
                check(locked.await(10, TimeUnit.SECONDS))
                assertTimeoutPreemptively(Duration.ofSeconds(3)) { assertThat(store.claim(now, Duration.ofSeconds(60), 100)).isEmpty() }
            } finally { release.countDown(); holder.get(10, TimeUnit.SECONDS) }
        }
        assertThat(store.claim(now, Duration.ofSeconds(60), 100)).hasSize(1)
    }
    @Test fun `Kafka 장애 동안 PENDING을 보존하고 복구 후 같은 메시지를 토픽에서 읽는다`() {
        record()
        val settings = PushOutboxProperties()
        KafkaContainer("apache/kafka-native:3.8.0").use { kafka ->
            kafka.start()
            val factory = KafkaPushConfig().pushKafkaProducerFactory(kafka.bootstrapServers, settings)
            val template = KafkaPushConfig().pushKafkaTemplate(factory)
            KafkaPushPublisher(template, mapper).use { publisher ->
                val docker = org.testcontainers.DockerClientFactory.instance().client()
                docker.pauseContainerCmd(kafka.containerId).exec()
                try {
                    PushOutboxRelay(store, publisher, settings, SimpleMeterRegistry(), Clock.fixed(now, ZoneOffset.UTC)).poll()
                    assertThat(jdbc.queryForObject("SELECT status FROM push_outbox", String::class.java)).isEqualTo("PENDING")
                    assertThat(jdbc.queryForObject("SELECT attempts FROM push_outbox", Int::class.java)).isEqualTo(1)
                } finally { docker.unpauseContainerCmd(kafka.containerId).exec() }
                val consumerSettings = mapOf<String, Any>(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to UUID.randomUUID().toString(), ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java)
                KafkaConsumer<String, String>(consumerSettings).use { consumer ->
                    consumer.subscribe(listOf("push.requested"))
                    PushOutboxRelay(store, publisher, settings, SimpleMeterRegistry(), Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC)).poll()
                    assertThat(jdbc.queryForObject("SELECT status FROM push_outbox", String::class.java)).isEqualTo("PUBLISHED")
                    val received = mutableListOf<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>()
                    val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
                    while (received.isEmpty() && System.nanoTime() < deadline) received.addAll(consumer.poll(Duration.ofMillis(500)).toList())
                    assertThat(received).isNotEmpty()
                    assertThat(received.first().key()).isEqualTo(user.publicId.toString())
                    assertThat(mapper.readValue(received.first().value(), PushMessage::class.java)).isEqualTo(message())
                }
            }
            factory.destroy()
        }
    }

    private fun publicIdRow(id: Long, publicId: java.util.UUID) = object : com.knk.manyak.auth.repository.UserPublicIdRow {
        override val id = id
        override val publicId = publicId
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
class OutboxTransactionTestConfig
