package com.knk.manyak.auth.repository

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface SocialAccountRepository : JpaRepository<SocialAccount, Long> {
    /**
     * 살아 있는 연동만 찾는다(KNK-1053). 로그인 조회는 반드시 이쪽을 쓴다 —
     * tombstone(`deleted_at` non-null)이 매칭되면 탈퇴한 계정으로 로그인된다.
     */
    fun findByProviderAndProviderUserIdAndDeletedAtIsNull(
        provider: SocialProvider,
        providerUserId: String,
    ): SocialAccount?

    /**
     * 상태 무관 조회. tombstone까지 본다 — 재가입(KNK-1053)·계정 연동이 그 행을 claim해야 하고,
     * `(provider, provider_user_id)` 유니크가 새 행 insert를 막기 때문이다.
     */
    fun findByProviderAndProviderUserId(
        provider: SocialProvider,
        providerUserId: String,
    ): SocialAccount?

    /**
     * 그 소셜 신원을 소유한 회원 id만 돌려준다(KNK-1053). 엔티티가 아니라 스칼라라 **영속성 컨텍스트에 올라가지
     * 않는다** — 재가입은 이 값으로 소유자 행을 먼저 잠근 뒤 소셜 행들을 처음 읽어야 잠금 이후의 커밋 상태를 본다
     * (엔티티를 미리 읽어 두면 1차 캐시가 잠금 전 스냅샷을 돌려준다).
     */
    @Query("SELECT s.userId FROM SocialAccount s WHERE s.provider = :provider AND s.providerUserId = :providerUserId")
    fun findOwnerUserId(
        @Param("provider") provider: SocialProvider,
        @Param("providerUserId") providerUserId: String,
    ): Long?

    /**
     * 그 회원의 연동 전부(tombstone 포함). 탈퇴가 이 목록을 tombstone으로 바꾸므로 필터를 두지 않는다.
     * 조회 호출부가 안전한 근거: tombstone은 탈퇴 트랜잭션에서만 생기고 그 트랜잭션이 소유자를 DELETED로
     * 함께 바꾸므로, ACTIVE 회원의 행에는 `deleted_at`이 붙지 않는다(ACTIVE 게이트가 곧 tombstone 게이트다).
     */
    fun findByUserId(userId: Long): List<SocialAccount>

    /**
     * 로그인 시각을 **살아 있는 연동에만** 기록한다(KNK-1053, Codex 재리뷰 P2). 갱신 행 수를 반환한다(0이면 그 사이 탈퇴).
     *
     * 엔티티 dirty checking으로 `lastLoginAt`을 바꾸면 안 되는 이유: [SocialAccount]에 `@Version`도 `@DynamicUpdate`도
     * 없어 UPDATE가 **전 컬럼**을 로그인 시점 스냅샷으로 덮는다. 로그인이 행을 읽은 뒤 탈퇴가 `deleted_at`·`email` 삭제를
     * 먼저 커밋하면, 늦게 flush되는 로그인 UPDATE가 tombstone을 풀고 **파기한 이메일까지 되살린다**(그 뒤 DELETED 회원이
     * 살아 있는 연동을 갖게 되어 재가입 경로에도 못 들어간다). 단일 컬럼 + `deleted_at IS NULL` 조건부 UPDATE면
     * 덮어쓸 컬럼도, 되살릴 tombstone도 없다.
     *
     * 벌크 갱신이라 `@PreUpdate`가 돌지 않으므로 `updatedAt`을 함께 쓴다. 갱신 후 1차 캐시의 스냅샷은 낡으므로
     * 비우고(`clearAutomatically`), 대기 중인 쓰기는 먼저 내보낸다(`flushAutomatically`).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE SocialAccount s SET s.lastLoginAt = :now, s.updatedAt = :now
        WHERE s.id = :id AND s.deletedAt IS NULL
        """,
    )
    fun touchLastLoginAt(@Param("id") id: Long, @Param("now") now: Instant): Int

    /** 계정 연동(KNK-739): 재인증 대상 확인과 provider 중복 판정에 쓴다. (user_id, provider)는 유니크다(V52). */
    fun findByUserIdAndProvider(
        userId: Long,
        provider: SocialProvider,
    ): SocialAccount?
}
