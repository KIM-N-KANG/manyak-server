package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.global.error.CodedResponseStatusException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class ChatGenerationStoreTests {
    private val mapper = jacksonObjectMapper()

    @Test fun `lastTurnId 생략과 명시적 null 및 정수를 구분한다`() {
        assertThat(mapper.readValue("""{"userInput":"진행"}""", ContinueChatRequest::class.java).hasLastTurnId).isFalse()
        val explicit = mapper.readValue("""{"userInput":"진행","lastTurnId":null}""", ContinueChatRequest::class.java)
        assertThat(explicit.hasLastTurnId).isTrue()
        assertThat(explicit.lastTurnId).isNull()
        val numbered = mapper.readValue("""{"userInput":"진행","lastTurnId":123}""", ContinueChatRequest::class.java)
        assertThat(numbered.hasLastTurnId).isTrue()
        assertThat(numbered.lastTurnId).isEqualTo(123)
    }

    @Test fun `실제 Redis 선점 TTL 만료 및 이전 생성의 원자적 해제를 검증한다`() {
        val factory = LettuceConnectionFactory("localhost", System.getProperty("spring.data.redis.port").toInt())
        factory.afterPropertiesSet()
        try {
            val redis = StringRedisTemplate(factory)
            val store = ChatGenerationStore(redis, mapper)
            val chat = UUID.randomUUID().toString()
            val first = store.acquire(chat, "CONTINUE", "입력", Instant.now(), null)
            assertThat(store.get(chat)).isEqualTo(first)
            assertThat(redis.getExpire("chat:$chat:generating")).isBetween(148, 150)
            assertThatThrownBy { store.acquire(chat, "REGENERATE", "입력", Instant.now(), 1) }
                .isInstanceOfSatisfying(CodedResponseStatusException::class.java) { assertThat(it.errorCode).isEqualTo("CHAT_TURN_IN_PROGRESS") }
            redis.expire("chat:$chat:generating", 1, TimeUnit.MILLISECONDS)
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (redis.hasKey("chat:$chat:generating") && System.nanoTime() < limit) Thread.yield()
            val next = store.acquire(chat, "REGENERATE", "다음", Instant.now(), 1)
            store.release(chat, first.generationId)
            assertThat(store.get(chat)).isEqualTo(next)
            store.release(chat, next.generationId)
            assertThat(store.get(chat)).isNull()
        } finally { factory.destroy() }
    }
    @Test fun `두 기기의 실제 Redis 동시 SET NX 중 한 요청만 선점한다`() {
        val factory = LettuceConnectionFactory("localhost", System.getProperty("spring.data.redis.port").toInt())
        factory.afterPropertiesSet()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val store = ChatGenerationStore(StringRedisTemplate(factory), mapper)
            val chat = UUID.randomUUID().toString()
            val go = java.util.concurrent.CountDownLatch(1)
            val futures = (1..2).map { device -> pool.submit<String> {
                go.await()
                try { store.acquire(chat, "CONTINUE", "입력 $device", Instant.now(), null); "acquired" }
                catch (ex: CodedResponseStatusException) { ex.errorCode }
            } }
            go.countDown()
            assertThat(futures.map { it.get(5, TimeUnit.SECONDS) }).containsExactlyInAnyOrder("acquired", "CHAT_TURN_IN_PROGRESS")
        } finally { pool.shutdownNow(); factory.destroy() }
    }

    @Test fun `실제 Redis 연결 획득 오류는 SERVICE_UNAVAILABLE 503으로 변환한다`() {
        val factory = org.mockito.Mockito.mock(org.springframework.data.redis.connection.RedisConnectionFactory::class.java)
        org.mockito.Mockito.`when`(factory.connection).thenThrow(org.springframework.data.redis.RedisConnectionFailureException("redis down"))
        val store = ChatGenerationStore(StringRedisTemplate(factory), mapper)
        assertThatThrownBy { store.acquire(UUID.randomUUID().toString(), "CONTINUE", "입력", Instant.now(), null) }
            .isInstanceOfSatisfying(CodedResponseStatusException::class.java) {
                assertThat(it.errorCode).isEqualTo("SERVICE_UNAVAILABLE")
                assertThat(it.statusCode.value()).isEqualTo(503)
            }
    }

}
