package com.knk.manyak.auth.token

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * RefreshTokenStore의 테스트용 in-memory 페이크(family 의미).
 *
 * Redis 인프라 없이 family 계약(생성/회전/재사용 탐지/family·사용자 단위 폐기, 만료)을 검증한다.
 * 단일 스레드 사용을 전제로 하므로 회전의 원자성은 자명하다(맵 연산 사이 끼어듦 없음).
 * 주입 가능한 Clock으로 만료 경계도 테스트할 수 있다.
 */
class InMemoryRefreshTokenStore(
    private val clock: Clock = Clock.systemUTC(),
) : RefreshTokenStore {

    /** family 레코드: 소유자 + 현재 유효한 토큰 해시 + 만료. */
    private data class Family(val userId: Long, val currentTokenHash: String, val expiresAt: Instant)

    /** 발급된 토큰 해시 → familyId (과거 토큰 포함, 재사용 탐지용) + 만료. */
    private data class TokenRef(val familyId: String, val expiresAt: Instant)

    private val families = mutableMapOf<String, Family>()
    private val tokens = mutableMapOf<String, TokenRef>()

    override fun createFamily(tokenHash: String, userId: Long, ttl: Duration): String {
        val familyId = UUID.randomUUID().toString()
        if (ttl.isZero || ttl.isNegative) return familyId // 즉시 만료: 저장하지 않는다.
        val expiresAt = clock.instant().plus(ttl)
        families[familyId] = Family(userId = userId, currentTokenHash = tokenHash, expiresAt = expiresAt)
        tokens[tokenHash] = TokenRef(familyId = familyId, expiresAt = expiresAt)
        return familyId
    }

    override fun rotate(presentedTokenHash: String, newTokenHash: String, ttl: Duration): RotateResult {
        if (ttl.isZero || ttl.isNegative) return RotateResult.Invalid // 회전은 양수 TTL 전제(실제 refreshTtl).
        val tokenRef = tokens[presentedTokenHash]?.takeUnless { isExpired(it.expiresAt) } ?: return RotateResult.Invalid
        val family = families[tokenRef.familyId]?.takeUnless { isExpired(it.expiresAt) } ?: return RotateResult.Invalid
        if (family.currentTokenHash != presentedTokenHash) {
            // 과거(이미 회전된) 토큰의 재사용 → family 전체 폐기(탈취 정황).
            revokeFamily(tokenRef.familyId)
            return RotateResult.ReuseDetected(family.userId)
        }
        val expiresAt = clock.instant().plus(ttl)
        families[tokenRef.familyId] = family.copy(currentTokenHash = newTokenHash, expiresAt = expiresAt)
        // 과거 토큰 매핑(presentedTokenHash)은 남겨 둔다 — 이후 재사용 시 family를 찾아 탐지하기 위해.
        tokens[newTokenHash] = TokenRef(familyId = tokenRef.familyId, expiresAt = expiresAt)
        return RotateResult.Rotated(family.userId)
    }

    override fun revokeFamilyByToken(tokenHash: String) {
        val familyId = tokens[tokenHash]?.familyId ?: return
        revokeFamily(familyId)
    }

    override fun revokeAllForUser(userId: Long) {
        families.filterValues { it.userId == userId }.keys.toList().forEach { revokeFamily(it) }
    }

    private fun revokeFamily(familyId: String) {
        families.remove(familyId)
        // 해당 family의 토큰 매핑도 함께 제거한다(남아도 family가 없어 Invalid이지만 흔적을 정리한다).
        tokens.entries.removeIf { it.value.familyId == familyId }
    }

    private fun isExpired(expiresAt: Instant): Boolean = !expiresAt.isAfter(clock.instant())
}
