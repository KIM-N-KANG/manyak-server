package com.knk.manyak.auth.token

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * RefreshTokenStore의 family 계약(생성/회전/재사용 탐지/family·사용자 단위 폐기, 만료)을 고정한다.
 *
 * Redis 인프라에 의존하지 않고 in-memory 페이크([InMemoryRefreshTokenStore])로 검증한다.
 * [RedisRefreshTokenStore]는 같은 계약을 만족해야 하며, 실제 Redis 동작은 별도(슬라이스) 테스트로 둔다.
 */
class RefreshTokenStoreContractTest {

    private val ttl = Duration.ofMinutes(30)
    private val store: RefreshTokenStore = InMemoryRefreshTokenStore()

    @Test
    fun `createFamily 후 현재 토큰으로 회전하면 Rotated와 userId를 돌려준다`() {
        store.createFamily(tokenHash = "t1", userId = 42L, ttl = ttl)

        assertThat(store.rotate("t1", "t2", ttl)).isEqualTo(RotateResult.Rotated(42L))
    }

    @Test
    fun `회전하면 새 토큰이 현재 토큰이 되어 다시 회전된다`() {
        store.createFamily("t1", 1L, ttl)
        store.rotate("t1", "t2", ttl)

        assertThat(store.rotate("t2", "t3", ttl)).isEqualTo(RotateResult.Rotated(1L))
    }

    @Test
    fun `알 수 없는 토큰 회전은 Invalid다`() {
        assertThat(store.rotate("unknown", "new", ttl)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `이미 회전된 과거 토큰을 재사용하면 ReuseDetected이고 family 전체가 폐기된다`() {
        store.createFamily("t1", 7L, ttl)
        store.rotate("t1", "t2", ttl) // t1은 과거 토큰, t2가 현재.

        // 과거 토큰(t1) 재사용 → 재사용 탐지.
        assertThat(store.rotate("t1", "tx", ttl)).isEqualTo(RotateResult.ReuseDetected(7L))
        // family 전체 폐기 → 현재 토큰이던 t2도 더 이상 회전되지 않는다.
        assertThat(store.rotate("t2", "ty", ttl)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `revokeFamilyByToken은 현재 토큰의 family를 폐기해 이후 회전이 Invalid다`() {
        store.createFamily("t1", 1L, ttl)

        store.revokeFamilyByToken("t1")

        assertThat(store.rotate("t1", "t2", ttl)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `revokeFamilyByToken은 과거 토큰으로도 family 전체를 폐기한다 (logout vs 동시 회전 경합 차단)`() {
        store.createFamily("t1", 1L, ttl)
        store.rotate("t1", "t2", ttl) // 동시 회전이 새 토큰(t2)을 발급한 상황 모사.

        // 로그아웃이 과거 토큰(t1)을 제시해도 family 전체가 폐기되어 현재 토큰(t2)까지 무효화된다.
        store.revokeFamilyByToken("t1")

        assertThat(store.rotate("t2", "t3", ttl)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `revokeFamilyByToken은 멱등하다 (알 수 없는 토큰 무시)`() {
        store.revokeFamilyByToken("never-issued")
    }

    @Test
    fun `revokeAllForUser는 해당 사용자의 모든 family를 폐기하고 다른 사용자는 보존한다`() {
        store.createFamily("a1", 99L, ttl)
        store.createFamily("a2", 99L, ttl) // 같은 사용자의 두 번째 세션.
        store.createFamily("b1", 100L, ttl)

        store.revokeAllForUser(99L)

        assertThat(store.rotate("a1", "x", ttl)).isEqualTo(RotateResult.Invalid)
        assertThat(store.rotate("a2", "y", ttl)).isEqualTo(RotateResult.Invalid)
        assertThat(store.rotate("b1", "z", ttl)).isEqualTo(RotateResult.Rotated(100L))
    }

    @Test
    fun `TTL이 0 이하로 만든 family는 즉시 만료되어 회전이 Invalid다`() {
        store.createFamily("t0", 1L, Duration.ZERO)

        assertThat(store.rotate("t0", "t1", ttl)).isEqualTo(RotateResult.Invalid)
    }

    @Test
    fun `만료된 family는 회전되지 않는다`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        var now = start
        val clock = object : Clock() {
            override fun instant(): Instant = now
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?): Clock = this
        }
        val expiringStore = InMemoryRefreshTokenStore(clock)
        expiringStore.createFamily("t1", 1L, Duration.ofMinutes(10))

        now = start.plus(Duration.ofMinutes(11)) // 만료 경과.

        assertThat(expiringStore.rotate("t1", "t2", ttl)).isEqualTo(RotateResult.Invalid)
    }
}
