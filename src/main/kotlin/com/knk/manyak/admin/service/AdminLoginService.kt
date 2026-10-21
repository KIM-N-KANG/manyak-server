package com.knk.manyak.admin.service

import com.knk.manyak.admin.dto.AdminLoginResponse
import com.knk.manyak.admin.dto.AdminMemberSummary
import com.knk.manyak.auth.consent.SocialAuthPrivacyFilter
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.SocialIdTokenVerifier
import com.knk.manyak.global.observability.StructuredLogger
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class AdminLoginService(
    private val verifiers: Map<SocialProvider, SocialIdTokenVerifier>,
    private val socials: SocialAccountRepository,
    private val users: UserRepository,
    private val eligibility: AdminEligibilityService,
    private val tokens: JwtTokenProvider,
    private val events: StructuredLogger,
) {
    fun login(idToken: String): AdminLoginResponse = SocialAuthPrivacyFilter.safely {
        val identity = try {
            verifiers.getValue(SocialProvider.GOOGLE).verify(idToken)
        } catch (_: ResponseStatusException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        }
        val social = socials.findByProviderAndProviderUserIdAndDeletedAtIsNull(SocialProvider.GOOGLE, identity.providerUserId)
        val user = social?.let { users.findById(it.userId).orElse(null) }
        val rejection = eligibility.rejectionReason(user)
        if (rejection != null) {
            events.event("admin_login_rejected", "reason" to rejection)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 없습니다.")
        }
        val admin = requireNotNull(user)
        val token = tokens.issueAdminToken(admin.publicId)
        events.event("admin_login_succeeded", "admin_public_id" to admin.publicId.toString())
        AdminLoginResponse(token, tokens.adminTtlSeconds(), AdminMemberSummary.from(admin, true))
    }
}
