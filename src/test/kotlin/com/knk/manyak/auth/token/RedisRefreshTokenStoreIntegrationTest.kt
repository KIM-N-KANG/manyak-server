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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
class RedisRefreshTokenStoreIntegrationTest : RefreshTokenStoreContractTest() {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var redisStore: RedisRefreshTokenStore

    override val store: RedisRefreshTokenStore get() = redisStore

    @BeforeEach
    fun flush() {
        redisTemplate.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
    }

    @Test
    fun `Redis createFamily는 family·토큰·사용자 인덱스 키를 TTL과 함께 만든다`() {
        val familyId = store.createFamily(tokenHash = "t1", userId = 42L, ttl = ttl)

        assertThat(redisTemplate.opsForValue().get("rt:fam:$familyId")).isEqualTo("42:t1")
        assertThat(redisTemplate.opsForValue().get("rt:tok:t1")).isEqualTo(familyId)
        assertThat(redisTemplate.opsForSet().isMember("rt:user:42", familyId)).isTrue()
        assertThat(redisTemplate.getExpire("rt:fam:$familyId")).isGreaterThan(0)
        assertThat(redisTemplate.getExpire("rt:tok:t1")).isGreaterThan(0)
    }

    @Test
    fun `Redis TTL이 0 이하면 아무 키도 만들지 않는다`() {
        val familyId = store.createFamily("t0", 1L, Duration.ZERO)

        assertThat(redisTemplate.hasKey("rt:fam:$familyId")).isFalse()
        assertThat(redisTemplate.hasKey("rt:tok:t0")).isFalse()
    }

    @Test
    fun `Redis 현재 토큰 회전은 Rotated이고 새 토큰이 현재가 된다`() {
        val familyId = store.createFamily("t1", 1L, ttl)

        assertThat(store.rotate("t1", "t2", "t2", ttl, Duration.ZERO)).isEqualTo(RotateResult.Rotated(1L))
        assertThat(redisTemplate.opsForValue().get("rt:fam:$familyId")).isEqualTo("1:t2")
        assertThat(redisTemplate.opsForValue().get("rt:tok:t2")).isEqualTo(familyId)
        // 새 토큰으로 다시 회전된다.
        assertThat(store.rotate("t2", "t3", "t3", ttl, Duration.ZERO)).isEqualTo(RotateResult.Rotated(1L))
    }

    @Test
    fun `Redis 알 수 없는 토큰 회전은 Invalid다`() {
        assertThat(store.rotate("unknown", "new", "new", ttl, Duration.ZERO)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `Redis 과거 토큰 재사용은 ReuseDetected이고 family 레코드와 사용자 인덱스가 삭제된다`() {
        val familyId = store.createFamily("t1", 7L, ttl)
        store.rotate("t1", "t2", "t2", ttl, Duration.ZERO) // t1은 과거, t2가 현재.

        assertThat(store.rotate("t1", "tx", "tx", ttl, Duration.ZERO)).isEqualTo(RotateResult.ReuseDetected(7L))
        // family 레코드 삭제 → 현재 토큰이던 t2도 무효.
        assertThat(redisTemplate.hasKey("rt:fam:$familyId")).isFalse()
        assertThat(redisTemplate.opsForSet().isMember("rt:user:7", familyId)).isFalse()
        assertThat(store.rotate("t2", "ty", "ty", ttl, Duration.ZERO)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `Redis revokeFamilyByToken은 과거 토큰으로도 family 전체를 폐기한다 (logout vs 동시 회전 경합 차단)`() {
        val familyId = store.createFamily("t1", 1L, ttl)
        store.rotate("t1", "t2", "t2", ttl, Duration.ZERO) // 동시 회전이 새 토큰(t2)을 발급한 상황 모사.

        store.revokeFamilyByToken("t1") // 로그아웃이 과거 토큰을 제시.

        assertThat(redisTemplate.hasKey("rt:fam:$familyId")).isFalse()
        assertThat(store.rotate("t2", "t3", "t3", ttl, Duration.ZERO)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `Redis revokeFamilyByToken은 멱등하다`() {
        store.revokeFamilyByToken("never-issued") // 예외 없이 무시한다.
    }

    @Test
    fun `Redis revokeAllForUser는 사용자의 모든 family를 폐기하고 다른 사용자는 보존한다`() {
        store.createFamily("a1", 99L, ttl)
        store.createFamily("a2", 99L, ttl)
        store.createFamily("b1", 100L, ttl)

        store.revokeAllForUser(99L)

        assertThat(store.rotate("a1", "x", "x", ttl, Duration.ZERO)).isEqualTo(RotateResult.Invalid)
        assertThat(store.rotate("a2", "y", "y", ttl, Duration.ZERO)).isEqualTo(RotateResult.Invalid)
        assertThat(store.rotate("b1", "z", "z", ttl, Duration.ZERO)).isEqualTo(RotateResult.Rotated(100L))
        assertThat(redisTemplate.hasKey("rt:user:99")).isFalse()
    }

    @Test
    fun `Redis 회전은 family TTL을 갱신한다 (sliding)`() {
        val familyId = store.createFamily("t1", 1L, Duration.ofSeconds(60))

        store.rotate("t1", "t2", "t2", Duration.ofMinutes(30), Duration.ZERO)

        val famTtl = redisTemplate.getExpire("rt:fam:$familyId", TimeUnit.SECONDS)
        assertThat(famTtl).isGreaterThan(120)
    }

    @Test
    fun `Redis 유예 키는 밀리초 TTL로 원문과 해시를 저장하고 재응답은 family TTL을 갱신하지 않는다`() {
        val familyId = store.createFamily("g1", 42L, ttl)
        store.rotate("g1", "g2", "raw-g2", ttl, Duration.ofMillis(750))
        assertThat(redisTemplate.opsForValue().get("rt:grace:g1")).isEqualTo("g2:raw-g2")
        assertThat(redisTemplate.getExpire("rt:grace:g1", TimeUnit.MILLISECONDS)).isBetween(1L, 750L)
        val before = redisTemplate.getExpire("rt:fam:$familyId", TimeUnit.MILLISECONDS)

        assertThat(store.rotate("g1", "unused", "unused-raw", Duration.ofHours(1), Duration.ofSeconds(10)))
            .isEqualTo(RotateResult.GraceReplayed(42L, "raw-g2"))
        assertThat(redisTemplate.getExpire("rt:fam:$familyId", TimeUnit.MILLISECONDS)).isBetween(1L, before)
        assertThat(redisTemplate.hasKey("rt:tok:unused")).isFalse()
    }

    @Test
    fun `Redis 회전은 짧게 남은 이전 토큰 매핑 TTL을 유예 시간까지 연장한다`() {
        store.createFamily("near-expiry", 42L, ttl)
        redisTemplate.expire("rt:tok:near-expiry", Duration.ofSeconds(2)) // PEXPIRE로 만료 경계 모사.
        val grace = Duration.ofSeconds(10)

        assertThat(store.rotate("near-expiry", "current", "current-raw", ttl, grace))
            .isEqualTo(RotateResult.Rotated(42L))

        val mappingTtl = redisTemplate.getExpire("rt:tok:near-expiry", TimeUnit.MILLISECONDS)
        val graceTtl = redisTemplate.getExpire("rt:grace:near-expiry", TimeUnit.MILLISECONDS)
        // 조회까지 경과한 시간을 허용하면서 두 키가 유예 전체 기간을 덮는지 확인한다.
        assertThat(mappingTtl).isBetween(9_000L, grace.toMillis())
        assertThat(mappingTtl).isGreaterThanOrEqualTo(graceTtl)
        assertThat(store.rotate("near-expiry", "unused", "unused-raw", ttl, grace))
            .isEqualTo(RotateResult.GraceReplayed(42L, "current-raw"))
    }

    @Test
    fun `Redis 회전은 유예보다 긴 이전 토큰 매핑 TTL을 줄이지 않는다`() {
        store.createFamily("long-lived", 42L, ttl)
        val before = redisTemplate.getExpire("rt:tok:long-lived", TimeUnit.MILLISECONDS)

        store.rotate("long-lived", "current", "current-raw", ttl, Duration.ofSeconds(10))

        assertThat(redisTemplate.getExpire("rt:tok:long-lived", TimeUnit.MILLISECONDS))
            .isBetween(before - 1_000L, before)
    }

    @Test
    fun `Redis 유예 키 만료 뒤 재사용은 family와 사용자 인덱스를 폐기한다`() {
        val familyId = store.createFamily("g1", 42L, ttl)
        store.rotate("g1", "g2", "raw-g2", ttl, Duration.ofSeconds(10))
        redisTemplate.delete("rt:grace:g1") // TTL이 만료돼 키가 사라진 상태.

        assertThat(store.rotate("g1", "unused", "unused-raw", ttl, Duration.ofSeconds(10)))
            .isEqualTo(RotateResult.ReuseDetected(42L))
        assertThat(redisTemplate.hasKey("rt:fam:$familyId")).isFalse()
        assertThat(redisTemplate.opsForSet().isMember("rt:user:42", familyId)).isFalse()
    }

    @Test
    fun `Redis 같은 토큰의 동시 회전은 한 번만 회전하고 나머지는 같은 원문을 유예 응답한다`() {
        val familyId = store.createFamily("concurrent-old", 42L, ttl)
        val ready = CountDownLatch(3)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val futures = (1..3).map { index ->
                executor.submit<Pair<Int, RotateResult>> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "동시 회전 시작 신호 시간 초과" }
                    index to store.rotate(
                        "concurrent-old", "new-hash-$index", "new-raw-$index", ttl, Duration.ofSeconds(10),
                    )
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            val rotated = results.filter { it.second is RotateResult.Rotated }
            val replayed = results.map { it.second }.filterIsInstance<RotateResult.GraceReplayed>()

            assertThat(rotated).hasSize(1)
            assertThat(rotated.single().second).isEqualTo(RotateResult.Rotated(42L))
            assertThat(replayed).hasSize(2)
            val winner = rotated.single().first
            assertThat(replayed).allSatisfy { result ->
                assertThat(result).isEqualTo(RotateResult.GraceReplayed(42L, "new-raw-$winner"))
            }
            assertThat(redisTemplate.opsForValue().get("rt:fam:$familyId")).isEqualTo("42:new-hash-$winner")
            assertThat(store.rotate("new-hash-$winner", "next-hash", "next-raw", ttl, Duration.ofSeconds(10)))
                .isEqualTo(RotateResult.Rotated(42L))
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
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
