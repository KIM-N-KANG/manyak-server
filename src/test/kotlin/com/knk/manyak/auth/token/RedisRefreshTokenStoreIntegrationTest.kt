package com.knk.manyak.auth.token

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import redis.embedded.RedisServer
import java.net.ServerSocket
import java.time.Duration

/**
 * embedded-redis로 실제 Redis(7.x) 동작을 띄워 RedisRefreshTokenStore의 실동작을 검증한다.
 *
 * 계약(save/find/delete, 만료, 사용자 인덱스 기반 전체 무효화)을 in-memory 페이크가 아닌
 * 실제 Redis 명령으로 확인한다. @DataRedisTest로 StringRedisTemplate만 슬라이스 로딩한다.
 */
@DataRedisTest
@Import(RedisRefreshTokenStore::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRefreshTokenStoreIntegrationTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var store: RedisRefreshTokenStore

    @org.junit.jupiter.api.BeforeEach
    fun flush() {
        redisTemplate.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
    }

    @Test
    fun `저장한 토큰 해시로 userId를 조회한다`() {
        store.save(tokenHash = "hash-1", userId = 42L, ttl = Duration.ofMinutes(30))

        assertThat(store.findUserId("hash-1")).isEqualTo(42L)
    }

    @Test
    fun `저장하지 않은 토큰 해시는 null을 반환한다`() {
        assertThat(store.findUserId("unknown")).isNull()
    }

    @Test
    fun `save는 refresh 키에 TTL을 적용한다`() {
        store.save(tokenHash = "hash-ttl", userId = 1L, ttl = Duration.ofMinutes(30))

        val ttl = redisTemplate.getExpire("refresh:hash-ttl")
        assertThat(ttl).isGreaterThan(0)
    }

    @Test
    fun `삭제한 토큰 해시는 더 이상 조회되지 않는다`() {
        store.save(tokenHash = "hash-2", userId = 7L, ttl = Duration.ofMinutes(30))

        store.delete("hash-2")

        assertThat(store.findUserId("hash-2")).isNull()
    }

    @Test
    fun `TTL이 0 이하면 저장되지 않는다`() {
        store.save(tokenHash = "hash-zero", userId = 1L, ttl = Duration.ZERO)

        assertThat(store.findUserId("hash-zero")).isNull()
    }

    @Test
    fun `deleteAllForUser는 해당 사용자의 모든 토큰을 무효화한다`() {
        store.save(tokenHash = "hash-a", userId = 99L, ttl = Duration.ofMinutes(30))
        store.save(tokenHash = "hash-b", userId = 99L, ttl = Duration.ofMinutes(30))
        store.save(tokenHash = "hash-c", userId = 100L, ttl = Duration.ofMinutes(30))

        store.deleteAllForUser(99L)

        assertThat(store.findUserId("hash-a")).isNull()
        assertThat(store.findUserId("hash-b")).isNull()
        assertThat(store.findUserId("hash-c")).isEqualTo(100L)
    }

    companion object {
        private val redisPort = findFreePort()
        private val redisServer: RedisServer = RedisServer.newRedisServer()
            .port(redisPort)
            .build()

        @BeforeAll
        @JvmStatic
        fun startRedis() {
            redisServer.start()
        }

        @AfterAll
        @JvmStatic
        fun stopRedis() {
            redisServer.stop()
        }

        @JvmStatic
        @DynamicPropertySource
        fun redisProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { "localhost" }
            registry.add("spring.data.redis.port") { redisPort }
        }

        private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
