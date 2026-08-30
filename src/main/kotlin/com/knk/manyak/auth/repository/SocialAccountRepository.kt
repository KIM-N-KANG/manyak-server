package com.knk.manyak.auth.repository

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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
     * 재가입 claim 대상 행을 비관적 쓰기 락으로 잡는다(KNK-1053, `UserRepository.findByIdForUpdate` 관례).
     *
     * claim은 insert가 아니라 **기존 행 UPDATE**라, 신규 가입 경합을 막던 `(provider, provider_user_id)` 유니크가
     * 방어선이 되지 못한다(같은 행을 두 트랜잭션이 갱신하면 뒤가 그냥 덮어쓴다). 락으로 직렬화해야
     * 뒤늦은 요청이 "이미 claim됨"을 보고 기존 경합 복구 경로로 빠질 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SocialAccount s WHERE s.provider = :provider AND s.providerUserId = :providerUserId")
    fun findByProviderAndProviderUserIdForUpdate(
        @Param("provider") provider: SocialProvider,
        @Param("providerUserId") providerUserId: String,
    ): SocialAccount?

    /**
     * 그 회원의 연동 전부(tombstone 포함). 탈퇴가 이 목록을 tombstone으로 바꾸므로 필터를 두지 않는다.
     * 조회 호출부가 안전한 근거: tombstone은 탈퇴 트랜잭션에서만 생기고 그 트랜잭션이 소유자를 DELETED로
     * 함께 바꾸므로, ACTIVE 회원의 행에는 `deleted_at`이 붙지 않는다(ACTIVE 게이트가 곧 tombstone 게이트다).
     */
    fun findByUserId(userId: Long): List<SocialAccount>

    /** 계정 연동(KNK-739): 재인증 대상 확인과 provider 중복 판정에 쓴다. (user_id, provider)는 유니크다(V52). */
    fun findByUserIdAndProvider(
        userId: Long,
        provider: SocialProvider,
    ): SocialAccount?
}
