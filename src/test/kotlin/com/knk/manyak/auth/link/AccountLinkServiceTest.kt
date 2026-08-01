package com.knk.manyak.auth.link

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.SocialIdTokenVerifier
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.observability.analytics.AnalyticsErrorType
import com.knk.manyak.global.observability.analytics.ServerAnalytics
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
import java.util.Optional

/**
 * AccountLinkService의 계정 연동 계약을 고정한다(KNK-739 확정 설계).
 *
 * 프로토콜은 2단계다.
 * 1. `재인증`(이미 연동된 provider의 ID 토큰) → 일회용 `linkCode` 발급
 * 2. `연동`(연동할 provider의 ID 토큰 + linkCode) → social_accounts 1행 추가
 *
 * 핵심 불변:
 * - 재인증 실패는 사유를 가리지 않고 403 `REAUTH_FAILED`이고 **코드를 발급하지 않는다**(계정 존재 여부 비노출).
 * - 재인증 토큰은 최근에 발급된 것이어야 한다 — 예전에 받아둔 ID 토큰 재제출을 막는다(공용 기기 보호).
 * - `linkCode`는 **성공했을 때만** 소비한다. 403·409로 실패하면 미소비로 남아 TTL 안에서 재시도할 수 있다
 *   (실패할 때마다 재인증을 다시 요구하지 않는다 — 핸드오프와 같은 결).
 * - 신규 계정·신규 세션을 만들지 않는다(토큰·크레딧 협력자를 생성자에 두지 않아 구조적으로 불가능).
 * - `DELETED`·사용자 부재는 401, `SUSPENDED`는 403이다(`SuspensionGuard`는 DELETED를 통과시키므로 쓰지 않는다).
 */
class AccountLinkServiceTest {

    private val socialAccountRepository: SocialAccountRepository = mock(SocialAccountRepository::class.java)
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val linkCodeStore: LinkCodeStore = mock(LinkCodeStore::class.java)
    private val serverAnalytics: ServerAnalytics = mock(ServerAnalytics::class.java)

    private fun verifier(info: SocialUserInfo): SocialIdTokenVerifier = SocialIdTokenVerifier { info }

    private fun serviceWith(
        google: SocialIdTokenVerifier = verifier(freshInfo(GOOGLE_SUB)),
        kakao: SocialIdTokenVerifier = verifier(freshInfo(KAKAO_SUB)),
    ) = AccountLinkService(
        mapOf(SocialProvider.GOOGLE to google, SocialProvider.KAKAO to kakao),
        socialAccountRepository,
        userRepository,
        linkCodeStore,
        serverAnalytics,
        REAUTH_MAX_AGE,
    )

    private fun freshInfo(sub: String) = SocialUserInfo(providerUserId = sub, issuedAt = Instant.now())

    private fun reauthRequest(provider: SocialProvider = SocialProvider.GOOGLE) =
        SocialReauthRequest(provider = provider, idToken = "reauth-token")

    private fun linkRequest() = AccountLinkRequest(idToken = "target-token")

    /** 요청자(userId=1)는 활성 회원이고 GOOGLE이 이미 연동돼 있다. */
    private fun givenActiveUserWithGoogle() {
        `when`(userRepository.findById(USER_ID)).thenReturn(Optional.of(User(id = USER_ID, nickname = "연동회원")))
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.GOOGLE))
            .thenReturn(SocialAccount(userId = USER_ID, provider = SocialProvider.GOOGLE, providerUserId = GOOGLE_SUB))
    }

    private fun givenValidLinkCode() {
        `when`(linkCodeStore.findUserId(LINK_CODE)).thenReturn(USER_ID)
    }

    /** 저장소는 저장한 엔티티를 그대로 돌려준다(미스텁 mock의 null은 Kotlin 널 체크에 걸린다). */
    private fun givenSaveEchoesEntity() {
        `when`(socialAccountRepository.saveAndFlush(anySocialAccount()))
            .thenAnswer { it.arguments[0] as SocialAccount }
    }

    private fun anySocialAccount(): SocialAccount = any(SocialAccount::class.java)
        ?: SocialAccount(userId = 0, provider = SocialProvider.GOOGLE, providerUserId = "x")

    private fun assertCoded(status: HttpStatus, code: String, block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(CodedResponseStatusException::class.java)
            .extracting("statusCode", "errorCode")
            .containsExactly(status, code)
    }

    private fun codeConsumed(): Boolean =
        mockingDetails(linkCodeStore).invocations.any { it.method.name == "consume" }

    private fun failedEventErrorType(): AnalyticsErrorType =
        mockingDetails(serverAnalytics).invocations
            .single { it.method.name == "socialLinkFailed" }
            .getArgument(2)

    // ---- 1단계: 재인증 ----

    @Test
    fun `재인증에 성공하면 링크 코드를 발급한다`() {
        givenActiveUserWithGoogle()
        val issued = LinkCodeResponse(linkCode = LINK_CODE, expiresAt = Instant.now().plusSeconds(300))
        `when`(linkCodeStore.issue(USER_ID)).thenReturn(issued)

        val response = serviceWith().reauthenticate(USER_ID, reauthRequest())

        assertThat(response.linkCode).isEqualTo(LINK_CODE)
        assertThat(response.expiresAt).isEqualTo(issued.expiresAt)
    }

    @Test
    fun `재인증 토큰이 무효면 403 REAUTH_FAILED이고 코드를 발급하지 않는다`() {
        givenActiveUserWithGoogle()
        val badReauth = SocialIdTokenVerifier {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Google ID 토큰입니다.")
        }

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith(google = badReauth).reauthenticate(USER_ID, reauthRequest())
        }

        verify(linkCodeStore, never()).issue(USER_ID)
    }

    @Test
    fun `재인증 sub가 내 연동과 다르면 403 REAUTH_FAILED다`() {
        givenActiveUserWithGoogle()

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith(google = verifier(freshInfo("another-google-sub"))).reauthenticate(USER_ID, reauthRequest())
        }

        verify(linkCodeStore, never()).issue(USER_ID)
    }

    @Test
    fun `연동돼 있지 않은 provider로 재인증하면 403 REAUTH_FAILED다`() {
        `when`(userRepository.findById(USER_ID)).thenReturn(Optional.of(User(id = USER_ID, nickname = "연동회원")))
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO)).thenReturn(null)

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith().reauthenticate(USER_ID, reauthRequest(provider = SocialProvider.KAKAO))
        }
    }

    @Test
    fun `재인증 토큰이 허용 시간보다 오래됐으면 403 REAUTH_FAILED다`() {
        // linkCode를 도입해도 "예전에 받아둔 ID 토큰을 재인증에 제출"하는 경로는 그대로 열려 있다.
        givenActiveUserWithGoogle()
        val stale = SocialUserInfo(providerUserId = GOOGLE_SUB, issuedAt = Instant.now().minus(Duration.ofHours(1)))

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith(google = verifier(stale)).reauthenticate(USER_ID, reauthRequest())
        }
    }

    @Test
    fun `재인증 토큰에 발급 시각이 없으면 403 REAUTH_FAILED다`() {
        givenActiveUserWithGoogle()
        val noIat = SocialUserInfo(providerUserId = GOOGLE_SUB, issuedAt = null)

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith(google = verifier(noIat)).reauthenticate(USER_ID, reauthRequest())
        }
    }

    // ---- 2단계: 연동 ----

    @Test
    fun `연동에 성공하면 1행만 저장하고 링크 코드를 소비한다`() {
        givenActiveUserWithGoogle()
        givenValidLinkCode()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB)).thenReturn(null)
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO)).thenReturn(null)
        givenSaveEchoesEntity()

        serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest())

        val saved = mockingDetails(socialAccountRepository).invocations
            .single { it.method.name == "saveAndFlush" }
            .getArgument<SocialAccount>(0)
        assertThat(saved.userId).isEqualTo(USER_ID)
        assertThat(saved.provider).isEqualTo(SocialProvider.KAKAO)
        assertThat(saved.providerUserId).isEqualTo(KAKAO_SUB)
        // 연동은 로그인이 아니므로 마지막 로그인 시각을 남기지 않는다.
        assertThat(saved.lastLoginAt).isNull()
        verify(linkCodeStore).consume(LINK_CODE)
    }

    @Test
    fun `연동 성공은 provider를 소문자로 실어 분석 이벤트를 발행한다`() {
        givenActiveUserWithGoogle()
        givenValidLinkCode()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB)).thenReturn(null)
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO)).thenReturn(null)
        givenSaveEchoesEntity()

        serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest())

        val emitted = mockingDetails(serverAnalytics).invocations.single { it.method.name == "socialLinkSucceeded" }
        assertThat(emitted.getArgument<Long>(0)).isEqualTo(USER_ID)
        assertThat(emitted.getArgument<String>(1)).isEqualTo("kakao")
    }

    @Test
    fun `링크 코드가 없으면 403이고 아무것도 저장하지 않는다`() {
        givenActiveUserWithGoogle()

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith().link(USER_ID, SocialProvider.KAKAO, null, linkRequest())
        }

        verify(socialAccountRepository, never()).saveAndFlush(anySocialAccount())
    }

    @Test
    fun `링크 코드가 만료·소비됐으면 403이다`() {
        givenActiveUserWithGoogle()
        `when`(linkCodeStore.findUserId(LINK_CODE)).thenReturn(null)

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest())
        }
    }

    @Test
    fun `다른 사용자의 링크 코드면 403이다`() {
        givenActiveUserWithGoogle()
        `when`(linkCodeStore.findUserId(LINK_CODE)).thenReturn(OTHER_USER_ID)

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.REAUTH_FAILED) {
            serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest())
        }

        // 남의 코드를 소비해 버리면 그 사람의 연동 시도를 망친다.
        assertThat(codeConsumed()).isFalse()
    }

    @Test
    fun `삭제된 계정은 401이다`() {
        // SuspensionGuard는 DELETED를 통과시키므로 계정 상태를 직접 본다.
        `when`(userRepository.findById(USER_ID))
            .thenReturn(Optional.of(User(id = USER_ID, nickname = "탈퇴회원", status = UserStatus.DELETED)))
        givenValidLinkCode()

        assertThatThrownBy { serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest()) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")

        verify(socialAccountRepository, never()).saveAndFlush(anySocialAccount())
    }

    @Test
    fun `사용자 행이 없으면 401이다`() {
        `when`(userRepository.findById(USER_ID)).thenReturn(Optional.empty())
        givenValidLinkCode()

        assertThatThrownBy { serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest()) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `정지 계정은 403이다`() {
        `when`(userRepository.findById(USER_ID))
            .thenReturn(Optional.of(User(id = USER_ID, nickname = "정지회원", status = UserStatus.SUSPENDED)))
        givenValidLinkCode()

        assertThatThrownBy { serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest()) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("403 FORBIDDEN")

        verify(socialAccountRepository, never()).saveAndFlush(anySocialAccount())
    }

    @Test
    fun `연동 대상 토큰이 무효면 403 SOCIAL_TOKEN_INVALID이고 코드는 소비되지 않는다`() {
        givenActiveUserWithGoogle()
        givenValidLinkCode()
        val badTarget = SocialIdTokenVerifier {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Kakao ID 토큰입니다.")
        }

        assertCoded(HttpStatus.FORBIDDEN, ApiErrorCodes.SOCIAL_TOKEN_INVALID) {
            serviceWith(kakao = badTarget).link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest())
        }

        assertThat(codeConsumed()).`as`("실패는 미소비로 남겨 TTL 안에서 재시도할 수 있어야 한다").isFalse()
    }

    @Test
    fun `다른 회원에게 연동된 소셜 계정이면 409이고 코드는 소비되지 않는다`() {
        givenActiveUserWithGoogle()
        givenValidLinkCode()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB))
            .thenReturn(SocialAccount(userId = OTHER_USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB))

        assertCoded(HttpStatus.CONFLICT, ApiErrorCodes.SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER) {
            serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest())
        }

        verify(socialAccountRepository, never()).saveAndFlush(anySocialAccount())
        assertThat(codeConsumed()).isFalse()
    }

    @Test
    fun `이미 같은 provider가 연동돼 있으면 409다`() {
        givenActiveUserWithGoogle()
        givenValidLinkCode()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB)).thenReturn(null)
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO))
            .thenReturn(SocialAccount(userId = USER_ID, provider = SocialProvider.KAKAO, providerUserId = "other-kakao-sub"))

        assertCoded(HttpStatus.CONFLICT, ApiErrorCodes.PROVIDER_ALREADY_LINKED) {
            serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest())
        }

        assertThat(codeConsumed()).isFalse()
    }

    @Test
    fun `이미 내게 연동된 소셜 계정을 다시 요청해도 409다`() {
        // 같은 식별자 재요청을 멱등 성공으로 흘리지 않는다(티켓 확정 설계). 새 연동이 아니므로 충돌로 답한다.
        givenActiveUserWithGoogle()
        givenValidLinkCode()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB))
            .thenReturn(SocialAccount(userId = USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB))

        assertCoded(HttpStatus.CONFLICT, ApiErrorCodes.PROVIDER_ALREADY_LINKED) {
            serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest())
        }

        verify(socialAccountRepository, never()).saveAndFlush(anySocialAccount())
        assertThat(codeConsumed()).`as`("실패이므로 링크 코드는 미소비로 남는다").isFalse()
    }

    @Test
    fun `삽입 경합이 내 연동으로 확정돼도 409다`() {
        // 더블클릭으로 두 요청이 동시에 삽입을 시도한 경우. 진 쪽도 성공이 아니라 409다(사전 검사와 같은 규칙).
        givenActiveUserWithGoogle()
        givenValidLinkCode()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB))
            .thenReturn(null)
            .thenReturn(SocialAccount(userId = USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB))
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO)).thenReturn(null)
        `when`(socialAccountRepository.saveAndFlush(anySocialAccount()))
            .thenThrow(DataIntegrityViolationException("uq_social_accounts_user_provider"))

        assertCoded(HttpStatus.CONFLICT, ApiErrorCodes.PROVIDER_ALREADY_LINKED) {
            serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest())
        }

        assertThat(codeConsumed()).isFalse()
    }

    @Test
    fun `삽입 경합이면 재조회로 409를 확정한다`() {
        givenActiveUserWithGoogle()
        givenValidLinkCode()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB))
            .thenReturn(null)
            .thenReturn(SocialAccount(userId = OTHER_USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB))
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO)).thenReturn(null)
        `when`(socialAccountRepository.saveAndFlush(anySocialAccount()))
            .thenThrow(DataIntegrityViolationException("uq_social_accounts_provider_user"))

        assertCoded(HttpStatus.CONFLICT, ApiErrorCodes.SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER) {
            serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest())
        }
    }

    @Test
    fun `연동 실패는 error_type validation으로 실패 이벤트를 발행한다`() {
        givenActiveUserWithGoogle()
        givenValidLinkCode()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB))
            .thenReturn(SocialAccount(userId = OTHER_USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB))

        assertThatThrownBy { serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest()) }
            .isInstanceOf(CodedResponseStatusException::class.java)

        // 409·403·토큰 검증 실패는 validation으로 묶는다(동일/타 회원 여부는 프로퍼티로 나누지 않는다).
        assertThat(failedEventErrorType()).isEqualTo(AnalyticsErrorType.VALIDATION)
    }

    @Test
    fun `저장 실패 같은 내부 오류는 error_type server로 발행한다`() {
        givenActiveUserWithGoogle()
        givenValidLinkCode()
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB)).thenReturn(null)
        `when`(socialAccountRepository.findByUserIdAndProvider(USER_ID, SocialProvider.KAKAO)).thenReturn(null)
        `when`(socialAccountRepository.saveAndFlush(anySocialAccount())).thenThrow(IllegalStateException("boom"))

        assertThatThrownBy { serviceWith().link(USER_ID, SocialProvider.KAKAO, LINK_CODE, linkRequest()) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(failedEventErrorType()).isEqualTo(AnalyticsErrorType.SERVER)
    }

    // ---- 연동 상태(/auth/me) ----

    @Test
    fun `연동 상태는 소문자 provider를 고정 순서로 돌려준다`() {
        // FE 경로·NextAuth provider ID가 소문자라 계약을 소문자로 고정한다. 예약 enum은 노출하지 않는다.
        `when`(socialAccountRepository.findByUserId(USER_ID)).thenReturn(
            listOf(
                SocialAccount(userId = USER_ID, provider = SocialProvider.KAKAO, providerUserId = KAKAO_SUB),
                SocialAccount(userId = USER_ID, provider = SocialProvider.APPLE, providerUserId = "apple-sub"),
                SocialAccount(userId = USER_ID, provider = SocialProvider.GOOGLE, providerUserId = GOOGLE_SUB),
            ),
        )

        assertThat(serviceWith().linkedProviders(USER_ID)).containsExactly("google", "kakao")
    }

    @Test
    fun `연동이 없으면 빈 목록이다`() {
        `when`(socialAccountRepository.findByUserId(USER_ID)).thenReturn(emptyList())

        assertThat(serviceWith().linkedProviders(USER_ID)).isEmpty()
    }

    private companion object {
        const val USER_ID = 1L
        const val OTHER_USER_ID = 2L
        const val GOOGLE_SUB = "google-sub-1"
        const val KAKAO_SUB = "kakao-sub-1"
        const val LINK_CODE = "link-code-1"
        val REAUTH_MAX_AGE: Duration = Duration.ofMinutes(10)
    }
}
