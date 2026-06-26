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
}
