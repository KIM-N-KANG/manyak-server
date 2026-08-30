package com.knk.manyak.auth.repository

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import org.springframework.data.jpa.repository.JpaRepository

interface SocialAccountRepository : JpaRepository<SocialAccount, Long> {
    // 소셜 로그인 시 (provider, provider_user_id)로 기존 연동 계정을 찾는다.
    fun findByProviderAndProviderUserId(
        provider: SocialProvider,
        providerUserId: String,
    ): SocialAccount?

    fun findByUserId(userId: Long): List<SocialAccount>

    /** 회원 탈퇴(KNK-1019): 소셜 연결을 전부 끊어 같은 소셜 계정의 재가입이 새 계정이 되게 한다. */
    fun deleteByUserId(userId: Long)

    /** 계정 연동(KNK-739): 재인증 대상 확인과 provider 중복 판정에 쓴다. (user_id, provider)는 유니크다(V52). */
    fun findByUserIdAndProvider(
        userId: Long,
        provider: SocialProvider,
    ): SocialAccount?
}
