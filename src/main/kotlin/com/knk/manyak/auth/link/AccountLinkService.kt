package com.knk.manyak.auth.link

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.entity.wireValue
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.SocialIdTokenVerifier
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.observability.analytics.AnalyticsErrorType
import com.knk.manyak.global.observability.analytics.ServerAnalytics
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

/**
 * 계정 연동 — 로그인된 세션에서 다른 소셜 provider를 같은 `user_id`에 추가한다(KNK-739).
 *
 * Google·Kakao는 `(provider, provider_user_id)`가 달라 별개 계정이 되고, 갈라진 계정을 사후에 합치는 merge는
 * 도입하지 않았다(스펙 §4-5). 그래서 **갈라지기 전에 막는 연동**이 유일한 대응책이다.
 *
 * 프로토콜은 2단계다.
 * 1. [reauthenticate] — 이미 연동된 provider의 ID 토큰으로 소유를 재확인하고 일회용 [LinkCodeStore] 코드를 발급한다.
 * 2. [link] — 연동할 provider의 ID 토큰과 그 코드를 함께 받아 `social_accounts` 1행을 추가한다.
 *
 * 2단계로 나눈 이유는 프론트엔드 현실이다. 소셜 ID 토큰은 OAuth 콜백의 서버 사이드에만 존재하는데, 한 요청에 두
 * provider의 ID 토큰을 함께 요구하면 첫 토큰을 두 번째 OAuth 리다이렉트 너머까지 보관해야 한다. 링크 코드는
 * 민감한 ID 토큰을 서버가 즉시 소비하고 리다이렉트를 건너는 건 짧은 수명의 불투명 코드뿐이게 만든다.
 *
 * 지키는 불변:
 * - **재인증 선행** — 연동은 계정에 로그인 수단을 영구히 추가하는 작업이라 현재 세션만으로는 부족하다. 세션을
 *   주운 사람(공용 기기 포함)이 자기 소셜 계정을 붙이면 세션을 정리해도 영구 접근권이 남는다.
 * - **재인증 토큰 신선도** — 예전에 받아둔 ID 토큰 재제출을 막는다([reauthMaxAge]). 판정 불가(`iat` 부재)는 거부한다.
 * - **신규 계정·신규 세션 없음** — 로그인 경로를 타지 않는다. 토큰·크레딧·게스트 시드 협력자를 생성자에 두지
 *   않아 구조적으로 불가능하며, `migrated_at`·`signup:{userId}` 같은 1회성 마커도 건드리지 않는다.
 * - **연동 해제 없음**(범위 밖). 해제 후 그 provider로 로그인하면 로그인 경로의 find-or-create가 새 계정을 만들어
 *   계정이 다시 갈라지고, 되돌리려면 merge가 필요한데 미도입이다.
 *
 * 실패는 세션 문제가 아니므로 401을 쓰지 않는다(401은 클라이언트가 세션 만료로 오인해 로그아웃한다).
 * 자격증명류 실패는 403 + [ApiErrorCodes] 코드로 구분한다. 예외는 계정 자체가 사라진 경우(`DELETED`·부재)로, 그때는 401이다.
 */
@Service
class AccountLinkService(
    private val verifiers: Map<SocialProvider, SocialIdTokenVerifier>,
    private val socialAccountRepository: SocialAccountRepository,
    private val userRepository: UserRepository,
    private val linkCodeStore: LinkCodeStore,
    private val serverAnalytics: ServerAnalytics,
    // 재인증 ID 토큰의 발급 시각(iat) 허용 범위. 소셜 팝업 왕복·사용자 조작 시간을 감안한 기본값이다.
    @param:Value("\${manyak.auth.link.reauth-max-age:PT10M}")
    private val reauthMaxAge: Duration,
) {

    /**
     * 1단계. 이미 연동된 provider로 소유를 재확인하고 일회용 링크 코드를 발급한다.
     * 실패 사유(토큰 무효·미연동 provider·sub 불일치·오래된 토큰)는 구분하지 않는다 — 어떤 소셜 계정이 이 회원에게
     * 붙어 있는지 노출하지 않기 위해서다.
     */
    fun reauthenticate(userId: Long, request: SocialReauthRequest): LinkCodeResponse {
        requireLinkableAccount(userId)
        val verifier = verifierOrBadRequest(request.provider)

        val info = try {
            verifier.verify(request.idToken)
        } catch (ex: Exception) {
            throw reauthFailed(ex)
        }
        // 발급 시각이 없으면 신선도를 판정할 수 없다 → 통과시키지 않는다(fail-closed).
        val issuedAt = info.issuedAt ?: throw reauthFailed(null)
        if (issuedAt.isBefore(Instant.now().minus(reauthMaxAge).minusSeconds(CLOCK_SKEW_SECONDS))) {
            throw reauthFailed(null)
        }
        val linked = socialAccountRepository.findByUserIdAndProvider(userId, request.provider)
            ?: throw reauthFailed(null)
        if (linked.providerUserId != info.providerUserId) {
            throw reauthFailed(null)
        }
        return linkCodeStore.issue(userId)
    }

    /**
     * 2단계. 연동할 provider의 ID 토큰과 링크 코드를 받아 연동 행을 추가한다.
     *
     * **의도적으로 트랜잭션을 열지 않는다.** 쓰기는 단일 행 삽입이고 정합성은 DB 유니크 두 개
     * (`(provider, provider_user_id)`, V52 `(user_id, provider)`)가 보장한다. 바깥 트랜잭션을 열면 삽입 실패
     * ([DataIntegrityViolationException])를 잡는 순간 그 트랜잭션이 rollback-only로 오염돼, 경합을 흡수해
     * 응답하려는 순간 커밋이 터진다. OSIV도 꺼져 있어 저장소 호출마다 독립 트랜잭션·영속성 컨텍스트다.
     */
    fun link(userId: Long, provider: SocialProvider, linkCode: String?, request: AccountLinkRequest) {
        try {
            val verifier = verifierOrBadRequest(provider)
            requireLinkCode(userId, linkCode)
            requireLinkableAccount(userId)

            val target = try {
                verifier.verify(request.idToken)
            } catch (ex: Exception) {
                throw CodedResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ApiErrorCodes.SOCIAL_TOKEN_INVALID,
                    "연동할 소셜 계정을 확인하지 못했습니다.",
                    ex,
                )
            }

            requireNoConflict(userId, provider, target.providerUserId)
            try {
                socialAccountRepository.saveAndFlush(
                    SocialAccount(
                        userId = userId,
                        provider = provider,
                        providerUserId = target.providerUserId,
                        email = target.email,
                        connectedAt = Instant.now(),
                        // 연동은 로그인이 아니다 — 그 provider로 실제 로그인할 때 로그인 경로가 채운다.
                    ),
                )
            } catch (ex: DataIntegrityViolationException) {
                // 사전 검사 통과 뒤 상대 요청이 먼저 커밋한 경합. 유니크 위반을 500으로 흘리지 않고 같은 규칙으로 사유를 확정한다.
                requireNoConflict(userId, provider, target.providerUserId)
                throw ex
            }
            // 성공했을 때만 코드를 소비한다. 실패는 미소비로 남겨 TTL 안에서 재인증 없이 재시도할 수 있게 한다.
            linkCodeStore.consume(linkCode!!)
            serverAnalytics.socialLinkSucceeded(userId, provider.wireValue)
        } catch (ex: Exception) {
            serverAnalytics.socialLinkFailed(userId, provider.wireValue, classifyLinkError(ex))
            throw ex
        }
    }

    /**
     * `GET /auth/me`가 노출하는 연동 상태. 값은 **소문자**로 고정하고(프론트 경로·NextAuth provider ID가 소문자)
     * 정렬은 enum 선언 순으로 고정한다. 로그인 경로가 없는 예약 provider(APPLE·NAVER)는 노출하지 않는다.
     */
    fun linkedProviders(userId: Long): List<String> =
        socialAccountRepository.findByUserId(userId)
            .map { it.provider }
            .filter { verifiers.containsKey(it) }
            .distinct()
            .sortedBy { it.ordinal }
            .map { it.wireValue }

    /** 링크 코드가 유효하고 **내 것**이어야 한다. 없음·만료·소비됨·타인 소유는 사유를 구분하지 않고 403이다. */
    private fun requireLinkCode(userId: Long, linkCode: String?) {
        val ownerId = linkCode?.takeIf { it.isNotBlank() }?.let { linkCodeStore.findUserId(it) }
        if (ownerId == null || ownerId != userId) {
            throw reauthFailed(null)
        }
    }

    /**
     * 연동 가능한 계정 상태인지 본다. 상태별 코드(DELETED 401 · SUSPENDED 403)는 `SuspensionGuard`와 같지만,
     * 그 가드는 **사용자 행이 없으면 통과**시킨다. 연동은 사용자 부재도 401로 막아야 하므로 여기서 직접 본다
     * — 탈퇴했거나 사라진 계정에 로그인 수단을 붙이면 되살아난다.
     */
    private fun requireLinkableAccount(userId: Long) {
        val user = userRepository.findById(userId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        when (user.status) {
            UserStatus.DELETED -> throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
            UserStatus.SUSPENDED -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "정지된 계정입니다.")
            UserStatus.ACTIVE -> Unit
        }
    }

    /**
     * 연동을 막는 충돌을 확정한다. 그 소셜 계정 행이 이미 있으면 **소유자와 무관하게 409**다(티켓 확정 설계) —
     * 같은 식별자 재요청을 멱등 성공으로 흘리지 않는다. 최종 상태는 어느 쪽이든 같고, 클라이언트는 409를 받아도
     * `GET /auth/me`로 현재 연동 상태를 정확히 보여줄 수 있다.
     *
     * - 그 소셜 계정이 다른 회원 것: `SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER` — 이 경우가 곧 "이미 갈라진" 상태이고
     *   합치는 것은 merge라 범위 밖이다
     * - 그 소셜 계정이 이미 내 것이거나, 내게 그 provider가 이미 있음: `PROVIDER_ALREADY_LINKED` — 교체는 해제가
     *   필요한데 범위 밖이다
     *
     * code를 둘로 나누는 이유는 프론트 안내가 갈리기 때문이다("이미 연동돼 있습니다" vs "다른 마냑 계정에 연결된
     * 계정입니다"). 이 정보를 얻으려면 그 소셜 계정의 유효한 ID 토큰이 필요하고 그건 이미 소유자라는 뜻이라
     * 열거 오라클이 되지 않는다.
     */
    private fun requireNoConflict(userId: Long, provider: SocialProvider, providerUserId: String) {
        val existing = socialAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)
        if (existing != null) {
            throw if (existing.userId == userId) {
                alreadyLinked()
            } else {
                CodedResponseStatusException(
                    HttpStatus.CONFLICT,
                    ApiErrorCodes.SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER,
                    "이미 다른 계정에 연동된 소셜 계정입니다.",
                )
            }
        }
        if (socialAccountRepository.findByUserIdAndProvider(userId, provider) != null) {
            throw alreadyLinked()
        }
    }

    private fun alreadyLinked() = CodedResponseStatusException(
        HttpStatus.CONFLICT,
        ApiErrorCodes.PROVIDER_ALREADY_LINKED,
        "이미 연동된 로그인 방식입니다.",
    )

    /**
     * 실패를 분석용 error_type으로 분류한다(스펙 §6-6-7 3값 유지).
     * provider·JWK 네트워크 실패는 network, ID 토큰 검증 실패·403·409 같은 요청 측 실패는 validation, 나머지(DB·Redis)는 server다.
     */
    private fun classifyLinkError(ex: Exception): AnalyticsErrorType {
        if (AnalyticsErrorType.fromThrowable(ex) == AnalyticsErrorType.NETWORK) return AnalyticsErrorType.NETWORK
        return if (ex is ResponseStatusException && ex.statusCode.is4xxClientError) {
            AnalyticsErrorType.VALIDATION
        } else {
            AnalyticsErrorType.SERVER
        }
    }

    /** 로그인 경로가 배선된 provider만 연동 대상이다(APPLE·NAVER는 enum 예약분). */
    private fun verifierOrBadRequest(provider: SocialProvider): SocialIdTokenVerifier =
        verifiers[provider]
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 로그인 방식입니다.")

    private fun reauthFailed(cause: Throwable?) = CodedResponseStatusException(
        HttpStatus.FORBIDDEN,
        ApiErrorCodes.REAUTH_FAILED,
        "본인 확인에 실패했습니다. 이미 연동된 방식으로 다시 인증해 주세요.",
        cause,
    )

    private companion object {
        /** 재인증 신선도 판정의 시계 오차 허용치(초). Nimbus 기본 skew와 같은 감각으로 둔다. */
        const val CLOCK_SKEW_SECONDS = 60L
    }
}
