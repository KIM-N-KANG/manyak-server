package com.knk.manyak.auth.token

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import java.util.concurrent.TimeUnit

/**
 * embedded-redis로 실제 Redis(7.x)를 띄워 RedisRefreshTokenStore의 family 동작을 검증한다.
 *
 * family 계약(생성/회전/재사용 탐지/폐기)을 in-memory 페이크가 아닌 실제 Redis 명령(Lua 포함)으로 확인한다.
 * @DataRedisTest로 StringRedisTemplate만 슬라이스 로딩한다.
 */
@DataRedisTest
@Import(RedisRefreshTokenStore::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRefreshTokenStoreIntegrationTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var store: RedisRefreshTokenStore

    private val ttl = Duration.ofMinutes(30)

    @BeforeEach
    fun flush() {
        redisTemplate.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
    }

    @Test
    fun `createFamily는 family·토큰·사용자 인덱스 키를 TTL과 함께 만든다`() {
        val familyId = store.createFamily(tokenHash = "t1", userId = 42L, ttl = ttl)

        assertThat(redisTemplate.opsForValue().get("rt:fam:$familyId")).isEqualTo("42:t1")
        assertThat(redisTemplate.opsForValue().get("rt:tok:t1")).isEqualTo(familyId)
        assertThat(redisTemplate.opsForSet().isMember("rt:user:42", familyId)).isTrue()
        assertThat(redisTemplate.getExpire("rt:fam:$familyId")).isGreaterThan(0)
        assertThat(redisTemplate.getExpire("rt:tok:t1")).isGreaterThan(0)
    }

    @Test
    fun `TTL이 0 이하면 아무 키도 만들지 않는다`() {
        val familyId = store.createFamily("t0", 1L, Duration.ZERO)

        assertThat(redisTemplate.hasKey("rt:fam:$familyId")).isFalse()
        assertThat(redisTemplate.hasKey("rt:tok:t0")).isFalse()
    }

    @Test
    fun `현재 토큰 회전은 Rotated이고 새 토큰이 현재가 된다`() {
        val familyId = store.createFamily("t1", 1L, ttl)

        assertThat(store.rotate("t1", "t2", ttl)).isEqualTo(RotateResult.Rotated(1L))
        assertThat(redisTemplate.opsForValue().get("rt:fam:$familyId")).isEqualTo("1:t2")
        assertThat(redisTemplate.opsForValue().get("rt:tok:t2")).isEqualTo(familyId)
        // 새 토큰으로 다시 회전된다.
        assertThat(store.rotate("t2", "t3", ttl)).isEqualTo(RotateResult.Rotated(1L))
    }

    @Test
    fun `알 수 없는 토큰 회전은 Invalid다`() {
        assertThat(store.rotate("unknown", "new", ttl)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `과거 토큰 재사용은 ReuseDetected이고 family 레코드와 사용자 인덱스가 삭제된다`() {
        val familyId = store.createFamily("t1", 7L, ttl)
        store.rotate("t1", "t2", ttl) // t1은 과거, t2가 현재.

        assertThat(store.rotate("t1", "tx", ttl)).isEqualTo(RotateResult.ReuseDetected(7L))
        // family 레코드 삭제 → 현재 토큰이던 t2도 무효.
        assertThat(redisTemplate.hasKey("rt:fam:$familyId")).isFalse()
        assertThat(redisTemplate.opsForSet().isMember("rt:user:7", familyId)).isFalse()
        assertThat(store.rotate("t2", "ty", ttl)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `revokeFamilyByToken은 과거 토큰으로도 family 전체를 폐기한다 (logout vs 동시 회전 경합 차단)`() {
        val familyId = store.createFamily("t1", 1L, ttl)
        store.rotate("t1", "t2", ttl) // 동시 회전이 새 토큰(t2)을 발급한 상황 모사.

        store.revokeFamilyByToken("t1") // 로그아웃이 과거 토큰을 제시.

        assertThat(redisTemplate.hasKey("rt:fam:$familyId")).isFalse()
        assertThat(store.rotate("t2", "t3", ttl)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `revokeFamilyByToken은 멱등하다`() {
        store.revokeFamilyByToken("never-issued") // 예외 없이 무시한다.
    }

    @Test
    fun `revokeAllForUser는 사용자의 모든 family를 폐기하고 다른 사용자는 보존한다`() {
        store.createFamily("a1", 99L, ttl)
        store.createFamily("a2", 99L, ttl)
        store.createFamily("b1", 100L, ttl)

        store.revokeAllForUser(99L)

        assertThat(store.rotate("a1", "x", ttl)).isEqualTo(RotateResult.Invalid)
        assertThat(store.rotate("a2", "y", ttl)).isEqualTo(RotateResult.Invalid)
        assertThat(store.rotate("b1", "z", ttl)).isEqualTo(RotateResult.Rotated(100L))
        assertThat(redisTemplate.hasKey("rt:user:99")).isFalse()
    }

    @Test
    fun `회전은 family TTL을 갱신한다 (sliding)`() {
        val familyId = store.createFamily("t1", 1L, Duration.ofSeconds(60))

        store.rotate("t1", "t2", Duration.ofMinutes(30))

        val famTtl = redisTemplate.getExpire("rt:fam:$familyId", TimeUnit.SECONDS)
        assertThat(famTtl).isGreaterThan(120)
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
