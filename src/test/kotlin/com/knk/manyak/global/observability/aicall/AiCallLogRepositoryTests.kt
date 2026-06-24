package com.knk.manyak.global.observability.aicall

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ai_call_logs 영속성 검증. 테스트는 Flyway 비활성 + H2 ddl-auto(create-drop)로 동작하므로
 * 엔티티 매핑이 스키마의 source of truth다. (운영 PostgreSQL은 V11 마이그레이션 + ddl-auto: validate)
 */
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
class AiCallLogRepositoryTests {

    @Autowired
    private lateinit var repository: AiCallLogRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `STARTED 상태와 retry_count 0 기본값으로 적재된다`() {
        val saved = repository.save(startedLog(feature = AiCallFeature.CHAT_RESPONSE))

        val found = repository.findById(saved.id).orElseThrow()
        assertEquals(AiCallStatus.STARTED, found.status)
        assertEquals(0, found.retryCount)
        assertEquals(AiCallFeature.CHAT_RESPONSE, found.feature)
        assertNull(found.completedAt)
        assertNull(found.latencyMs)
        assertNull(found.model)
        assertNull(found.inputTokenCount)
    }

    @Test
    fun `feature는 소문자 snake_case 문자열로 저장된다`() {
        val saved = repository.saveAndFlush(startedLog(feature = AiCallFeature.STORYLINE_GENERATION))
        entityManager.clear()

        val raw = entityManager
            .createNativeQuery("SELECT feature FROM ai_call_logs WHERE id = :id")
            .setParameter("id", saved.id)
            .singleResult
        assertEquals("storyline_generation", raw)

        val found = repository.findById(saved.id).orElseThrow()
        assertEquals(AiCallFeature.STORYLINE_GENERATION, found.feature)
    }

    @Test
    fun `markSucceeded는 상태·latency·완료시각을 채운다`() {
        val log = startedLog(feature = AiCallFeature.STORY_COMPLETION)
        val completedAt = Instant.now()
        log.markSucceeded(latencyMs = 1234L, completedAt = completedAt)

        val found = repository.findById(repository.save(log).id).orElseThrow()
        assertEquals(AiCallStatus.SUCCEEDED, found.status)
        assertEquals(1234L, found.latencyMs)
        assertEquals(completedAt, found.completedAt)
        assertNull(found.errorCode)
    }

    @Test
    fun `markFailed는 상태·error_code·latency를 채운다`() {
        val log = startedLog(feature = AiCallFeature.CHAT_RESPONSE)
        log.markFailed(latencyMs = 50L, errorCode = "AI_STREAM_FAILED")

        val found = repository.findById(repository.save(log).id).orElseThrow()
        assertEquals(AiCallStatus.FAILED, found.status)
        assertEquals("AI_STREAM_FAILED", found.errorCode)
        assertEquals(50L, found.latencyMs)
    }

    @Test
    fun `식별자(session·anonymous·story·chat·turn)를 적재한다`() {
        val chatId = UUID.randomUUID()
        val saved = repository.save(
            AiCallLog(
                requestId = "req_identifiers",
                callerService = "manyak-server",
                feature = AiCallFeature.CHAT_RESPONSE,
                anonymousIdHash = "anon_hash_0123456789abcdef",
                sessionId = "sess_1",
                storyId = 7L,
                chatId = chatId,
                turnIndex = 3,
            ),
        )

        val found = repository.findById(saved.id).orElseThrow()
        assertEquals("sess_1", found.sessionId)
        assertEquals("anon_hash_0123456789abcdef", found.anonymousIdHash)
        assertEquals(7L, found.storyId)
        assertEquals(chatId, found.chatId)
        assertEquals(3, found.turnIndex)
    }

    @Test
    fun `prompt_versions를 JSONB로 round-trip 적재한다`() {
        val saved = repository.saveAndFlush(
            startedLog(feature = AiCallFeature.CHAT_RESPONSE).apply {
                promptVersions = linkedMapOf(
                    "SAFETY" to 1,
                    "CORE" to 2,
                    "STORY" to 3,
                    "CHARACTER" to 2,
                    "USER" to 2,
                    "MEMORY" to 2,
                )
            },
        )
        // 영속성 컨텍스트 캐시가 아니라 실제 DB 직렬화·역직렬화 경로를 통과시킨다.
        entityManager.clear()

        val found = repository.findById(saved.id).orElseThrow()
        assertEquals(
            mapOf("SAFETY" to 1, "CORE" to 2, "STORY" to 3, "CHARACTER" to 2, "USER" to 2, "MEMORY" to 2),
            found.promptVersions,
        )
    }

    @Test
    fun `applyMeta는 model·provider·토큰·retry·prompt_versions를 채운다`() {
        val log = startedLog(feature = AiCallFeature.STORYLINE_GENERATION).apply {
            applyMeta(
                AiCallMeta(
                    model = "deepseek-v4-pro",
                    provider = "deepseek",
                    inputTokenCount = 6180,
                    outputTokenCount = 512,
                    retryCount = 1,
                    promptVersions = mapOf("STORYLINES" to 2),
                ),
            )
        }
        val savedId = repository.saveAndFlush(log).id
        entityManager.clear()

        val found = repository.findById(savedId).orElseThrow()
        assertEquals("deepseek-v4-pro", found.model)
        assertEquals("deepseek", found.provider)
        assertEquals(6180, found.inputTokenCount)
        assertEquals(512, found.outputTokenCount)
        assertEquals(1, found.retryCount)
        assertEquals(mapOf("STORYLINES" to 2), found.promptVersions)
    }

    @Test
    fun `applyMeta의 null 필드는 덮어쓰지 않아 기본값을 보존한다`() {
        val log = startedLog(feature = AiCallFeature.CHAT_RESPONSE).apply {
            applyMeta(AiCallMeta(model = "deepseek-v4-flash"))
        }
        val savedId = repository.saveAndFlush(log).id
        entityManager.clear()

        val found = repository.findById(savedId).orElseThrow()
        assertEquals("deepseek-v4-flash", found.model)
        assertNull(found.inputTokenCount)
        assertNull(found.outputTokenCount)
        assertNull(found.promptVersions)
        assertEquals(0, found.retryCount)
    }

    private fun startedLog(feature: AiCallFeature) = AiCallLog(
        requestId = "req_test",
        callerService = "manyak-server",
        feature = feature,
    )
}
