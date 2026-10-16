package com.knk.manyak.auth.consent

import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.handoff.LoginHandoffService
import com.knk.manyak.auth.social.SocialAccountRegistrar
import com.knk.manyak.auth.social.SocialLoginService
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.user.consent.UserConsentRequest
import com.knk.manyak.user.consent.UserConsentService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class SocialConsentServiceTest {
    private val login = mock(SocialLoginService::class.java)
    private val registrar = mock(SocialAccountRegistrar::class.java)
    private val consents = mock(UserConsentService::class.java)
    private val codes = mock(ConsentTokenStore::class.java)
    private val handoffs = mock(LoginHandoffService::class.java)
    private val analytics = mock(com.knk.manyak.global.observability.analytics.ServerAnalytics::class.java)
    private val service = SocialConsentService(login, registrar, consents, codes, handoffs, analytics)

    @Test fun `경합 재조회 실패의 DB detail과 cause를 외부 예외에 연결하지 않는다`() {
        val pending = PendingSocialConsent(SocialProvider.GOOGLE, "private-sub", "private@example.com", null, null, null)
        val info = SocialUserInfo(pending.providerUserId, pending.email, null, null)
        val request = UserConsentRequest("v1.4", "v1.7", "1")
        `when`(codes.find("code")).thenReturn(pending)
        `when`(registrar.completeExistingAuthentication(SocialProvider.GOOGLE, info, request))
            .thenThrow(DataIntegrityViolationException("(provider, provider_user_id)=(GOOGLE, private-sub)", IllegalStateException("private@example.com")))
        val error = catchThrowable { service.complete("code", request, null) }
        assertThat(error).isInstanceOf(ResponseStatusException::class.java).hasNoCause()
        assertThat(error.stackTraceToString()).doesNotContain("private-sub", "private@example.com")
        verify(codes, never()).consume("code")
        verifyNoInteractions(login)
        verify(analytics).socialLoginFailed(SocialProvider.GOOGLE, com.knk.manyak.global.observability.analytics.AnalyticsErrorType.SERVER)
    }

    @Test fun `검증기 오류의 ID 토큰 원문과 cause도 노출하지 않는다`() {
        `when`(login.verifyIdentity(SocialProvider.GOOGLE, "private-id-token"))
            .thenThrow(ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid", IllegalArgumentException("private-id-token")))
        val error = catchThrowable { service.start(SocialProvider.GOOGLE, "private-id-token", null, null) }
        assertThat(error).isInstanceOf(ResponseStatusException::class.java).hasNoCause()
        assertThat(error.stackTraceToString()).doesNotContain("private-id-token")
        assertThat((error as ResponseStatusException).statusCode.value()).isEqualTo(401)
        verifyNoInteractions(registrar, codes, handoffs)
    }
    @Test fun `새 소셜 인증의 검증 후 DB 장애도 실패 이벤트를 한 번 발행한다`() {
        val info = SocialUserInfo("sub")
        `when`(login.verifyIdentity(SocialProvider.KAKAO, "token")).thenReturn(info)
        `when`(registrar.findForAuthentication(SocialProvider.KAKAO, info))
            .thenThrow(org.springframework.dao.DataAccessResourceFailureException("db unavailable"))
        val error = catchThrowable { service.start(SocialProvider.KAKAO, "token", null, null) }
        assertThat(error).isInstanceOf(ResponseStatusException::class.java).hasNoCause()
        verify(analytics).socialLoginFailed(SocialProvider.KAKAO, com.knk.manyak.global.observability.analytics.AnalyticsErrorType.SERVER)
        verifyNoMoreInteractions(analytics)
    }

    @Test fun `검증 실패와 무효 대기 코드는 후속 실패 이벤트를 중복 발행하지 않는다`() {
        `when`(login.verifyIdentity(SocialProvider.GOOGLE, "bad"))
            .thenThrow(ResponseStatusException(HttpStatus.UNAUTHORIZED))
        catchThrowable { service.start(SocialProvider.GOOGLE, "bad", null, null) }
        catchThrowable { service.complete("missing", UserConsentRequest(), null) }
        verifyNoInteractions(analytics)
    }

}
