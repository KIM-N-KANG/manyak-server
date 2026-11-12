package com.knk.manyak.auth.security

import com.knk.manyak.auth.consent.UserConsentService
import com.knk.manyak.auth.dto.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.service.AccountQueryService
import com.knk.manyak.global.security.AccountAccess
import com.knk.manyak.global.security.AccountAccessDecision
import com.knk.manyak.global.security.AccountAccessPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * global 보안 필터와 리졸버가 쓰는 계정 판정 포트의 구현(KNK-2068). 탈퇴 판정과 동의 게이트(ACCOUNT 5-1-1, 5-1-3)는 계정 정책이라 여기 둔다.
 *
 * 서블릿 `Filter`가 아니다. 필터는 global의 [com.knk.manyak.global.security.DeletedAccountRejectionFilter]이고 보안 체인에만 들어간다.
 */
@Component
class AccountAccessAdapter(
    private val userRepository: UserRepository,
    private val accounts: AccountQueryService,
    private val consentService: UserConsentService,
    @param:Value("\${manyak.auth.consent-gate.enabled:false}") private val consentGateEnabled: Boolean,
) : AccountAccessPort {

    override fun userIdOf(publicId: UUID): Long? = accounts.userIdOf(publicId)

    override fun check(publicId: UUID, method: String, path: String): AccountAccessDecision {
        val user = userRepository.findByPublicId(publicId) ?: return AccountAccessDecision(AccountAccess.ALLOWED, null)
        return AccountAccessDecision(access(user.id, user.status, method, path), user.id)
    }

    private fun access(userId: Long, status: UserStatus, method: String, path: String): AccountAccess {
        if (status == UserStatus.DELETED) return AccountAccess.DELETED
        if (!consentGateEnabled || consentAllowed(method, path)) return AccountAccess.ALLOWED
        val consents = consentService.authenticationStatus(userId)
        return if (consents.terms.needsConsent || consents.privacy.needsConsent || consents.age14.needsConsent) {
            AccountAccess.CONSENT_REQUIRED
        } else {
            AccountAccess.ALLOWED
        }
    }

    private fun consentAllowed(method: String, path: String): Boolean = when (method) {
        "GET" -> path in setOf(
            "/api/v1/users/me/consents", "/api/v1/auth/me", "/api/v1/credits/policies",
            "/api/v1/credits/products", "/api/v1/profile-presets", "/api/v1/stories/simple/tags",
            // KNK-1537의 장르 API는 구현 전부터 공개 카탈로그 허용 목록에 포함한다.
            "/api/v1/stories/genres", "/api/v1/stories/art-styles",
        )
        "POST" -> path == "/api/v1/users/me/consents"
        "DELETE" -> path == "/api/v1/users/me" || path == "/api/v1/users/me/push-tokens"
        else -> false
    }
}
