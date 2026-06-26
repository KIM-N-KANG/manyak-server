package com.knk.manyak.auth.token

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * RefreshTokenStore의 테스트용 in-memory 페이크.
 *
 * Redis 인프라 없이 계약(save/find/delete, 만료 의미)을 검증하기 위한 구현이다.
 * 만료는 저장 시점의 만료 시각으로 시뮬레이션하며, 조회/삭제 시 만료된 항목을 정리한다.
 * 주입 가능한 Clock으로 만료 경계도 테스트할 수 있다.
 */
class InMemoryRefreshTokenStore(
    private val clock: Clock = Clock.systemUTC(),
) : RefreshTokenStore {

    private data class Entry(val userId: Long, val expiresAt: Instant)

    private val entries = mutableMapOf<String, Entry>()

    override fun save(tokenHash: String, userId: Long, ttl: Duration) {
        if (ttl.isZero || ttl.isNegative) {
            entries.remove(tokenHash)
            return
        }
        entries[tokenHash] = Entry(userId = userId, expiresAt = clock.instant().plus(ttl))
    }

    override fun findUserId(tokenHash: String): Long? {
        val entry = entries[tokenHash] ?: return null
        if (isExpired(entry)) {
            entries.remove(tokenHash)
            return null
        }
        return entry.userId
    }

    override fun consume(tokenHash: String): Long? {
        // 조회+삭제를 한 번에. 단일 스레드 페이크라 원자성은 자명하다(removeIf/remove 사이 끼어듦 없음).
        val entry = entries.remove(tokenHash) ?: return null
        // 만료된 항목은 소비되지 않은 것으로 본다(이미 remove로 정리됨).
        return if (isExpired(entry)) null else entry.userId
    }

    override fun delete(tokenHash: String) {
        entries.remove(tokenHash)
    }

    override fun deleteAllForUser(userId: Long) {
        entries.entries.removeIf { it.value.userId == userId }
    }

    private fun isExpired(entry: Entry): Boolean = !entry.expiresAt.isAfter(clock.instant())
}
