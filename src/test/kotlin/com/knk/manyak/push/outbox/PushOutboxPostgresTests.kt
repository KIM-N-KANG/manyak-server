package com.knk.manyak.push.outbox

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
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.*
import java.util.*
import java.util.function.Supplier
import java.util.concurrent.*

/** 테스트 전용 컨테이너만 사용한다. 앱의 datasource/Flyway 프로파일 격리는 변경하지 않는다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushOutboxPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "outbox-test")
        .withEnv("POSTGRES_DB", "outbox").withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private val now = Instant.parse("2026-09-25T00:00:00Z")
    private val mapper = jacksonObjectMapper()
    private lateinit var jdbc: JdbcTemplate
    private lateinit var tx: TransactionTemplate
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var store: PushOutboxStore
    private val user = User(id = 7, nickname = "제작자", servicePushEnabled = false)
    private val event = StoryCompletedEvent(7, UUID.randomUUID(), UUID.randomUUID().toString(), "완성 제목")

    @BeforeAll fun start() {
        pg.start()
        val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/outbox", "postgres", "outbox-test")
        jdbc = JdbcTemplate(ds)
        val migration = java.io.File("src/main/resources/db/migration/V86__create_push_outbox.sql").readText()
        jdbc.execute(migration)
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
            mock(UserRepository::class.java).also { `when`(it.findById(7)).thenReturn(Optional.of(user)) }
        })
        context.register(PushOutboxStore::class.java, StoryCompletionOutboxListener::class.java)
        context.refresh()
        store = context.getBean(PushOutboxStore::class.java)
    }
    @AfterAll fun stop() { if (::context.isInitialized) context.close(); pg.stop() }
    @BeforeEach fun clean() { jdbc.update("DELETE FROM push_outbox"); MDC.clear() }
    @AfterEach fun clearMdc() { MDC.clear() }
    private fun record() { tx.executeWithoutResult { context.publishEvent(event) } }
    private fun count() = jdbc.queryForObject("SELECT count(*) FROM push_outbox", Int::class.java)
    private fun message() = mapper.readValue(jdbc.queryForObject("SELECT payload::text FROM push_outbox", String::class.java), PushMessage::class.java)

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
                "deepLink" to "https://manyak.app/stories/${event.storyPublicId}"), requestId = "trace-request", sessionId = "trace-session"))
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
}

@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
class OutboxTransactionTestConfig
