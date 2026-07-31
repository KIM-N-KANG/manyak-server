package com.knk.manyak.auth.link

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.social.SocialIdTokenVerifier
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.security.SuspensionGuard
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

/**
 * AccountLinkService의 계정 연동 계약을 고정한다(스펙 §4-5 후속, KNK-738/739).
 *
 * 저장소·검증기는 mock으로 두고 판정과 부작용만 본다.
 *
 * 핵심 불변:
 * - 연동은 **이미 연동된 provider로의 재인증**이 선행돼야 한다(공용 기기에 남은 세션 악용 차단).
 *   재인증 실패는 사유를 가리지 않고 403 REAUTH_FAILED다(계정 존재 여부 비노출).
 * - 재인증 토큰은 **최근에 발급된 것**이어야 한다. 최초 로그인 때 받아 보관해 둔 ID 토큰을 재제출하면
 *   재인증이 형식만 남으므로 iat 신선도를 요구한다.
 * - **신규 계정·신규 세션을 만들지 않는다**(가입 보상·게스트 시드·토큰 발급 없음 — 저장은 social_accounts 1행뿐).
 * - 다른 회원에게 연동된 소셜 계정은 409다(합치는 것은 merge라 범위 밖).
 */
class AccountLinkServiceTest {

    private val socialAccountRepository: com.knk.manyak.auth.repository.SocialAccountRepository =
        mock(com.knk.manyak.auth.repository.SocialAccountRepository::class.java)
    private val suspensionGuard: SuspensionGuard = mock(SuspensionGuard::class.java)

    private fun verifier(info: SocialUserInfo): SocialIdTokenVerifier = SocialIdTokenVerifier { info }

    private fun serviceWith(
        google: SocialIdTokenVerifier = verifier(freshInfo(GOOGLE_SUB)),
        kakao: SocialIdTokenVerifier = verifier(freshInfo(KAKAO_SUB)),
    ) = AccountLinkService(
        mapOf(SocialProvider.GOOGLE to google, SocialProvider.KAKAO to kakao),
        socialAccountRepository,
        suspensionGuard,
        REAUTH_MAX_AGE,
    )

    private fun freshInfo(sub: String) = SocialUserInfo(providerUserId = sub, issuedAt = Instant.now())

    private fun linkRequest(
        provider: SocialProvider = SocialProvider.KAKAO,
        reauthProvider: SocialProvider = SocialProvider.GOOGLE,
    ) = AccountLinkRequest(
        provider = provider,
        idToken = "target-token",
        reauth = SocialReauthRequest(provider = reauthProvider, idToken = "reauth-token"),
    )

    /** 요청자(userId=1)에게 GOOGLE이 이미 연동돼 있는 기본 상태. */
    private fun givenGoogleLinked() {
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.GOOGLE))
            .thenReturn(SocialAccount(userId = USER_ID, provider = SocialProvider.GOOGLE, providerUserId = GOOGLE_SUB))
    }

    private fun anySocialAccount(): SocialAccount = any(SocialAccount::class.java)
        ?: SocialAccount(userId = 0, provider = SocialProvider.GOOGLE, providerUserId = "x")

    private fun assertCoded(status: HttpStatus, code: String, block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(CodedResponseStatusException::class.java)
            .extracting("statusCode", "errorCode")
            .containsExactly(status, code)
    }

    // ---- 재인증 ----

    @Test
    fun `재인증 토큰이 무효면 403 REAUTH_FAILED이고 저장하지 않는다`() {
        givenGoogleLinked()
        val badReauth = SocialIdTokenVerifier {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Google ID 토큰입니다.")
        }

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith(google = badReauth).link(USER_ID, linkRequest())
        }

        verify(socialAccountRepository, never()).saveAndFlush(anySocialAccount())
    }

    @Test
    fun `재인증 sub가 내 연동과 다르면 403 REAUTH_FAILED다`() {
        // 남의 구글 계정으로 재인증을 통과시키면 재인증이 무의미해진다.
        givenGoogleLinked()

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith(google = verifier(freshInfo("another-google-sub"))).link(USER_ID, linkRequest())
        }
    }

    @Test
    fun `연동돼 있지 않은 provider로 재인증하면 403 REAUTH_FAILED다`() {
        // 카카오는 아직 연동 전이므로 카카오로는 재인증할 수 없다.
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO)).thenReturn(null)

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith().link(USER_ID, linkRequest(provider = SocialProvider.GOOGLE, reauthProvider = SocialProvider.KAKAO))
        }
    }

    @Test
    fun `재인증 토큰이 허용 시간보다 오래됐으면 403 REAUTH_FAILED다`() {
        // 최초 로그인 때 받아 저장해 둔 ID 토큰 재제출을 막는다(공용 기기 시나리오).
        givenGoogleLinked()
        val stale = SocialUserInfo(providerUserId = GOOGLE_SUB, issuedAt = Instant.now().minus(Duration.ofHours(1)))

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith(google = verifier(stale)).link(USER_ID, linkRequest())
        }
    }

    @Test
    fun `재인증 토큰에 발급 시각이 없으면 403 REAUTH_FAILED다`() {
        // 신선도를 판정할 수 없으면 통과시키지 않는다(fail-closed).
        givenGoogleLinked()
        val noIat = SocialUserInfo(providerUserId = GOOGLE_SUB, issuedAt = null)

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith(google = verifier(noIat)).link(USER_ID, linkRequest())
        }
    }

    // ---- 대상 토큰·충돌 ----

    @Test
    fun `연동 대상 토큰이 무효면 403 SOCIAL_TOKEN_INVALID다`() {
        givenGoogleLinked()
        val badTarget = SocialIdTokenVerifier {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Kakao ID 토큰입니다.")
        }

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.SOCIAL_TOKEN_INVALID) {
            serviceWith(kakao = badTarget).link(USER_ID, linkRequest())
        }
    }

    @Test
    fun `다른 회원에게 연동된 소셜 계정이면 409다`() {
        givenGoogleLinked()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB))
            .thenReturn(SocialAccount(userId = OTHER_USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB))

        assertCoded(HttpStatus.CONFLICT, ApiErrorCodes.SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER) {
            serviceWith().link(USER_ID, linkRequest())
        }

        verify(socialAccountRepository, never()).saveAndFlush(anySocialAccount())
    }

    @Test
    fun `이미 같은 provider가 연동돼 있으면 409다`() {
        // 계정당 provider 1개(V52 유니크). 다른 카카오 계정으로 갈아끼우는 것은 해제가 필요한데 범위 밖이다.
        givenGoogleLinked()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB)).thenReturn(null)
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO))
            .thenReturn(SocialAccount(userId = USER_ID, provider = SocialProvider.KAKAO, providerUserId = "other-kakao-sub"))

        assertCoded(HttpStatus.CONFLICT, ApiErrorCodes.PROVIDER_ALREADY_LINKED) {
            serviceWith().link(USER_ID, linkRequest())
        }
    }

    @Test
    fun `이미 내게 연동된 소셜 계정을 다시 요청하면 멱등하게 성공한다`() {
        givenGoogleLinked()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB))
            .thenReturn(SocialAccount(userId = USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB))
        `when`(socialAccountRepository.findByUserId(USER_ID)).thenReturn(
            listOf(
                SocialAccount(userId = USER_ID, provider = SocialProvider.GOOGLE, providerUserId = GOOGLE_SUB),
                SocialAccount(userId = USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB),
            ),
        )

        val response = serviceWith().link(USER_ID, linkRequest())

        assertThat(response.links.filter { it.linked }.map { it.provider })
            .containsExactly(SocialProvider.GOOGLE, SocialProvider.KAKAO)
        verify(socialAccountRepository, never()).saveAndFlush(anySocialAccount())
    }

    // ---- 성공 경로 ----

    @Test
    fun `연동에 성공하면 social_accounts 1행만 저장하고 세션을 만들지 않는다`() {
        givenGoogleLinked()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB)).thenReturn(null)
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO)).thenReturn(null)
        `when`(socialAccountRepository.findByUserId(USER_ID)).thenReturn(
            listOf(
                SocialAccount(userId = USER_ID, provider = SocialProvider.GOOGLE, providerUserId = GOOGLE_SUB),
                SocialAccount(userId = USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB),
            ),
        )

        val response = serviceWith().link(USER_ID, linkRequest())

        val saved = mockingDetails(socialAccountRepository).invocations
            .single { it.method.name == "saveAndFlush" }
            .getArgument<SocialAccount>(0)
        assertThat(saved.userId).isEqualTo(USER_ID)
        assertThat(saved.provider).isEqualTo(SocialProvider.KAKAO)
        assertThat(saved.providerUserId).isEqualTo(KAKAO_SUB)
        // 연동은 로그인이 아니므로 마지막 로그인 시각은 비워 둔다(그 provider로 실제 로그인할 때 채워진다).
        assertThat(saved.lastLoginAt).isNull()
        assertThat(response.links).hasSize(2)
        // 이 서비스는 토큰 발급·크레딧 적립·게스트 시드 협력자를 아예 갖지 않는다(생성자 의존성으로 고정).
    }

    @Test
    fun `삽입 경합이면 재조회로 사유를 확정한다`() {
        // 사전 검사 통과 후 상대 요청이 먼저 커밋한 경우. 유니크 위반을 그대로 500으로 내지 않는다.
        givenGoogleLinked()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB))
            .thenReturn(null)
            .thenReturn(SocialAccount(userId = OTHER_USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB))
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO)).thenReturn(null)
        `when`(socialAccountRepository.saveAndFlush(anySocialAccount()))
            .thenThrow(DataIntegrityViolationException("uq_social_accounts_provider_user"))

        assertCoded(HttpStatus.CONFLICT, ApiErrorCodes.SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER) {
            serviceWith().link(USER_ID, linkRequest())
        }
    }

    @Test
    fun `삽입 경합이 내 연동으로 확정되면 멱등하게 성공한다`() {
        // 같은 연동을 두 번 눌러 두 요청이 동시에 삽입을 시도한 경우. 진 쪽도 200이어야 한다(500 금지).
        // 이 경로 때문에 link()는 바깥 트랜잭션을 열지 않는다 — 열면 여기서 rollback-only가 되어 커밋이 터진다.
        givenGoogleLinked()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB))
            .thenReturn(null)
            .thenReturn(SocialAccount(userId = USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB))
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO)).thenReturn(null)
        `when`(socialAccountRepository.saveAndFlush(anySocialAccount()))
            .thenThrow(DataIntegrityViolationException("uq_social_accounts_user_provider"))
        `when`(socialAccountRepository.findByUserId(USER_ID)).thenReturn(
            listOf(
                SocialAccount(userId = USER_ID, provider = SocialProvider.GOOGLE, providerUserId = GOOGLE_SUB),
                SocialAccount(userId = USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB),
            ),
        )

        val response = serviceWith().link(USER_ID, linkRequest())

        assertThat(response.links.filter { it.linked }.map { it.provider })
            .containsExactly(SocialProvider.GOOGLE, SocialProvider.KAKAO)
    }

    @Test
    fun `정지 계정은 연동할 수 없다`() {
        `when`(suspensionGuard.requireActive(USER_ID))
            .thenThrow(ResponseStatusException(HttpStatus.FORBIDDEN, "정지된 계정입니다."))

        assertThatThrownBy { serviceWith().link(USER_ID, linkRequest()) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("403 FORBIDDEN")

        verify(socialAccountRepository, never()).saveAndFlush(anySocialAccount())
    }

    // ---- 조회 ----

    @Test
    fun `연동 상태는 로그인 가능한 provider를 모두 내려준다`() {
        val connectedAt = Instant.parse("2026-07-30T12:00:00Z")
        `when`(socialAccountRepository.findByUserId(USER_ID)).thenReturn(
            listOf(
                SocialAccount(
                    userId = USER_ID,
                    provider = SocialProvider.GOOGLE,
                    providerUserId = GOOGLE_SUB,
                    connectedAt = connectedAt,
                ),
            ),
        )

        val response = serviceWith().getLinks(USER_ID)

        assertThat(response.links.map { it.provider })
            .containsExactly(SocialProvider.GOOGLE, SocialProvider.KAKAO)
        assertThat(response.links[0].linked).isTrue()
        assertThat(response.links[0].connectedAt).isEqualTo(connectedAt)
        assertThat(response.links[1].linked).isFalse()
        assertThat(response.links[1].connectedAt).isNull()
    }

    private companion object {
        const val USER_ID = 1L
        const val OTHER_USER_ID = 2L
        const val GOOGLE_SUB = "google-sub-1"
        const val KAKAO_SUB = "kakao-sub-1"
        val REAUTH_MAX_AGE: Duration = Duration.ofMinutes(10)
    }
}
