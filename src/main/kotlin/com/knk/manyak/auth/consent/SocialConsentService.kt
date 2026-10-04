package com.knk.manyak.auth.consent

import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.handoff.LoginHandoffService
import com.knk.manyak.auth.social.SocialAccountRegistrar
import com.knk.manyak.auth.social.SocialLoginService
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.user.consent.UserConsentRequest
import com.knk.manyak.user.consent.UserConsentService
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** 외부 트랜잭션 없이 계정과 동의의 원자적 저장, 멱등 후속 처리, 코드 소비 순으로 진행한다. */
@Service
class SocialConsentService(
    private val login: SocialLoginService,
    private val registrar: SocialAccountRegistrar,
    private val consents: UserConsentService,
    private val codes: ConsentTokenStore,
    private val handoffs: LoginHandoffService,
) {
    fun start(provider: SocialProvider, idToken: String, deviceId: String?, handoffCode: String?): SocialAuthResponse = safely {
        val info = try {
            login.verifyIdentity(provider, idToken)
        } catch (_: ResponseStatusException) {
            // JWT 라이브러리의 cause 메시지에 토큰 또는 클레임이 들어갈 수 있다.
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 ID 토큰입니다.")
        }
        val user = registrar.findForAuthentication(provider, info)
        val status = consents.authenticationStatus(user?.id)
        val handoff = handoffCode?.let(handoffs::find)
        val sourceDevice = handoff?.deviceId?.takeIf { it.isNotBlank() } ?: deviceId
        if (user != null && !status.terms.needsConsent && !status.privacy.needsConsent && !status.age14.needsConsent) {
            val confirmed = registrar.completeExistingAuthentication(provider, info, UserConsentRequest())
            if (confirmed != null) {
                return@safely SocialAuthResponse("COMPLETED", token = login.finishLogin(provider, confirmed, false, sourceDevice, handoffCode, handoff))
            }
        }
        // 조회 후 탈퇴한 경우에도 신규 계약으로 다시 동의를 받는다.
        val current = registrar.findForAuthentication(provider, info)
        val pending = PendingSocialConsent(provider, info.providerUserId, info.email, current?.id, sourceDevice, handoffCode)
        val issued = codes.issue(pending)
        SocialAuthResponse("CONSENT_REQUIRED", consentToken = issued.code, expiresAt = issued.expiresAt,
            isNewUser = current == null, consents = consents.authenticationStatus(current?.id))
    }

    fun complete(code: String?, request: UserConsentRequest, deviceId: String?): TokenResponse = safely {
        val pending = code?.takeIf { it.isNotBlank() }?.let(codes::find)
            ?: throw CodedResponseStatusException(HttpStatus.UNAUTHORIZED, ApiErrorCodes.CONSENT_TOKEN_INVALID,
                "소셜 로그인을 다시 시작해 주세요.")
        consents.validateVersions(request)
        val info = SocialUserInfo(pending.providerUserId, pending.email, null, null)
        val (user, created) = resolve(pending.provider, info, request)
        val handoff = pending.handoffCode?.let(handoffs::find)
        val sourceDevice = handoff?.deviceId?.takeIf { it.isNotBlank() } ?: pending.deviceId ?: deviceId
        login.finishLogin(pending.provider, user, created, sourceDevice, pending.handoffCode, handoff) {
            codes.consume(requireNotNull(code))
        }
    }

    private fun resolve(provider: SocialProvider, info: SocialUserInfo, request: UserConsentRequest): Pair<User, Boolean> {
        // 각 실패 트랜잭션이 끝난 뒤 새 조회로 소셜 유니크와 tombstone claim 경합을 복구한다.
        repeat(2) {
            try {
                registrar.completeExistingAuthentication(provider, info, request)?.let { return it to false }
                return registrar.createWithConsents(provider, info, request) to true
            } catch (_: DataIntegrityViolationException) {
                // 원시 예외는 소셜 sub와 이메일을 포함할 수 있어 기록하지 않는다.
            }
        }
        registrar.completeExistingAuthentication(provider, info, request)?.let { return it to false }
        throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.")
    }

    private fun <T> safely(block: () -> T): T = SocialAuthPrivacyFilter.protecting {
        try { block() } catch (_: DataAccessException) {
            // 원인 체인도 연결하지 않는다. GlobalExceptionHandler와 Sentry에는 안전한 예외만 전달한다.
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.")
        }
    }
}
