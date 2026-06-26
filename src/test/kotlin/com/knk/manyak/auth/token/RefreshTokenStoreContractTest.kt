package com.knk.manyak.auth.token

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * RefreshTokenStore의 계약(save/find/delete, 만료 의미)을 고정한다.
 *
 * Redis 인프라에 의존하지 않고 in-memory 페이크(InMemoryRefreshTokenStore)로 검증한다.
 * RedisRefreshTokenStore는 같은 계약을 만족해야 하며, 실제 Redis 동작 검증은 별도(슬라이스) 테스트로 둔다.
 */
class RefreshTokenStoreContractTest {

    private val store: RefreshTokenStore = InMemoryRefreshTokenStore()

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
    fun `삭제한 토큰 해시는 더 이상 조회되지 않는다`() {
        store.save(tokenHash = "hash-2", userId = 7L, ttl = Duration.ofMinutes(30))

        store.delete("hash-2")

        assertThat(store.findUserId("hash-2")).isNull()
    }

    @Test
    fun `없는 토큰 해시 삭제는 예외 없이 무시된다`() {
        store.delete("missing")

        assertThat(store.findUserId("missing")).isNull()
    }

    @Test
    fun `TTL이 0 이하면 즉시 만료되어 조회되지 않는다`() {
        store.save(tokenHash = "hash-3", userId = 1L, ttl = Duration.ZERO)

        assertThat(store.findUserId("hash-3")).isNull()
    }

    @Test
    fun `같은 토큰 해시를 다시 저장하면 최신 userId로 덮어쓴다`() {
        store.save(tokenHash = "hash-4", userId = 1L, ttl = Duration.ofMinutes(30))
        store.save(tokenHash = "hash-4", userId = 2L, ttl = Duration.ofMinutes(30))

        assertThat(store.findUserId("hash-4")).isEqualTo(2L)
    }

    @Test
    fun `consume는 첫 호출에 userId를 반환하고 둘째 호출은 null이다 (원자적 1회 소비)`() {
        store.save(tokenHash = "hash-consume", userId = 21L, ttl = Duration.ofMinutes(30))

        // 첫 소비: userId를 반환하고 동시에 제거한다.
        assertThat(store.consume("hash-consume")).isEqualTo(21L)
        // 둘째 소비(회전 후 재사용 시나리오): 이미 소비됐으므로 null.
        assertThat(store.consume("hash-consume")).isNull()
        // 이후 조회도 null.
        assertThat(store.findUserId("hash-consume")).isNull()
    }

    @Test
    fun `없는 토큰 해시 consume은 null이다`() {
        assertThat(store.consume("unknown")).isNull()
    }

    @Test
    fun `TTL이 0 이하로 저장된 토큰은 consume에서도 null이다`() {
        store.save(tokenHash = "hash-consume-expired", userId = 1L, ttl = Duration.ZERO)

        assertThat(store.consume("hash-consume-expired")).isNull()
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
}
