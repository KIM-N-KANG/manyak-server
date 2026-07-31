package com.knk.manyak.auth.link

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.social.SocialIdTokenVerifier
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.security.SuspensionGuard
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

/**
 * 계정 연동 — 로그인된 세션에서 다른 소셜 provider를 같은 `user_id`에 추가한다(KNK-738/739, 스펙 §4-5 후속).
 *
 * Google·Kakao는 `(provider, provider_user_id)`가 달라 별개 계정이 되고, 갈라진 계정을 사후에 합치는 merge는
 * 도입하지 않았다. 그래서 **갈라지기 전에 막는 연동**이 유일한 대응책이다.
 *
 * 이 서비스가 지키는 불변:
 * 1. **재인증 선행** — 이미 연동된 provider로 한 번 더 인증해야 진행한다. 연동은 계정에 로그인 수단을 영구히
 *    추가하는 보안 민감 작업이라, 공용 기기에 남은 세션을 주운 사람이 자기 소셜 계정을 붙여 영구 접근권을
 *    얻는 것을 막아야 한다. 재인증 토큰은 **방금 발급된 것**이어야 한다([reauthMaxAge]) — 최초 로그인 때 받아
 *    보관해 둔 ID 토큰을 재제출할 수 있으면 재인증이 형식만 남는다.
 * 2. **신규 계정·신규 세션 없음** — 로그인 경로를 타지 않으므로 가입 보상·게스트 체험 시드·핸드오프 소비·토큰
 *    발급이 일어나지 않는다. 저장은 `social_accounts` 1행뿐이다(협력자 목록에 토큰·크레딧 서비스가 아예 없다).
 * 3. **연동 해제 없음**(Phase 1 범위 밖). 해제 후 그 provider로 로그인하면 로그인 경로의 find-or-create가 새 계정을
 *    만들어 계정이 다시 갈라지고, 되돌리려면 merge가 필요한데 미도입이다.
 *
 * 실패는 세션 문제가 아니므로 401을 쓰지 않는다 — 401은 클라이언트가 "세션 만료"로 오인해 로그아웃을 유발한다.
 * 자격증명류 실패는 403 + [ApiErrorCodes] 코드로 사유를 구분한다.
 */
@Service
class AccountLinkService(
    private val verifiers: Map<SocialProvider, SocialIdTokenVerifier>,
    private val socialAccountRepository: SocialAccountRepository,
    private val suspensionGuard: SuspensionGuard,
    // 재인증 ID 토큰의 발급 시각(iat) 허용 범위. 소셜 팝업 왕복·사용자 조작 시간을 감안한 기본값이다.
    @param:Value("\${manyak.auth.link.reauth-max-age:PT10M}")
    private val reauthMaxAge: Duration,
) {

    /** 마이페이지용 연동 상태. 로그인 경로가 있는 provider를 연동 여부와 무관하게 모두, 고정 순서로 내려준다. */
    @Transactional(readOnly = true)
    fun getLinks(userId: Long): AccountLinkResponse {
        val linkedByProvider = socialAccountRepository.findByUserId(userId).associateBy { it.provider }
        return AccountLinkResponse(
            links = linkableProviders().map { provider ->
                val linked = linkedByProvider[provider]
                AccountLinkItem(
                    provider = provider,
                    linked = linked != null,
                    connectedAt = linked?.connectedAt,
                )
            },
        )
    }

    /**
     * 연동을 추가하고 최신 연동 상태를 반환한다. 이미 내게 연동된 소셜 계정을 다시 보내면 멱등하게 성공한다
     * (네트워크 재시도·더블클릭이 409로 보이지 않게 한다).
     *
     * **의도적으로 트랜잭션을 열지 않는다.** 쓰기는 단일 행 삽입이고 정합성은 DB 유니크 두 개가 보장하므로
     * 여러 문장을 묶을 이유가 없다. 오히려 바깥 트랜잭션을 열면 삽입 실패([DataIntegrityViolationException])를
     * 잡는 순간 그 트랜잭션이 rollback-only로 오염돼, 경합을 흡수해 200으로 답하려는 순간 커밋이 터진다
     * (같은 연동을 두 번 눌렀을 때 500). OSIV도 꺼져 있어 저장소 호출마다 독립 트랜잭션·영속성 컨텍스트다.
     */
    fun link(userId: Long, request: AccountLinkRequest): AccountLinkResponse {
        suspensionGuard.requireActive(userId)
        val targetVerifier = verifierOrBadRequest(request.provider)
        val reauthVerifier = verifierOrBadRequest(request.reauth.provider)

        requireReauthenticated(userId, request.reauth, reauthVerifier)

        val target = try {
            targetVerifier.verify(request.idToken)
        } catch (ex: Exception) {
            throw CodedResponseStatusException(
                HttpStatus.FORBIDDEN,
                ApiErrorCodes.SOCIAL_TOKEN_INVALID,
                "연동할 소셜 계정을 확인하지 못했습니다.",
                ex,
            )
        }

        conflictOrNull(userId, request.provider, target.providerUserId)?.let { return it }

        try {
            socialAccountRepository.saveAndFlush(newLink(userId, request.provider, target))
        } catch (ex: DataIntegrityViolationException) {
            // 사전 검사 통과 뒤 상대 요청이 먼저 커밋한 경합. 유니크 위반을 500으로 흘리지 않고 재조회로 사유를 확정한다.
            return conflictOrNull(userId, request.provider, target.providerUserId) ?: throw ex
        }
        return getLinks(userId)
    }

    /** 연동은 로그인이 아니므로 `lastLoginAt`을 남기지 않는다(그 provider로 실제 로그인할 때 로그인 경로가 채운다). */
    private fun newLink(userId: Long, provider: SocialProvider, target: SocialUserInfo) =
        SocialAccount(
            userId = userId,
            provider = provider,
            providerUserId = target.providerUserId,
            email = target.email,
            connectedAt = Instant.now(),
        )

    /**
     * 이미 연동된 provider로의 재인증을 요구한다. 실패 사유(토큰 무효·미연동 provider·sub 불일치·오래된 토큰)는
     * 구분하지 않고 하나의 코드로 답한다 — 어떤 소셜 계정이 이 회원에게 붙어 있는지 노출하지 않기 위해서다.
     */
    private fun requireReauthenticated(
        userId: Long,
        reauth: SocialReauthRequest,
        verifier: SocialIdTokenVerifier,
    ) {
        val info = try {
            verifier.verify(reauth.idToken)
        } catch (ex: Exception) {
            throw reauthFailed(ex)
        }
        // 발급 시각이 없으면 신선도를 판정할 수 없다 → 통과시키지 않는다(fail-closed).
        val issuedAt = info.issuedAt ?: throw reauthFailed(null)
        if (issuedAt.isBefore(Instant.now().minus(reauthMaxAge).minusSeconds(CLOCK_SKEW_SECONDS))) {
            throw reauthFailed(null)
        }
        val linked = socialAccountRepository.findByUserIdAndProvider(userId, reauth.provider)
            ?: throw reauthFailed(null)
        if (linked.providerUserId != info.providerUserId) {
            throw reauthFailed(null)
        }
    }

    /**
     * 삽입 전과 경합 후에 같은 규칙으로 판정한다.
     * - 그 소셜 계정이 이미 내 것: 멱등 성공(현재 상태 반환)
     * - 다른 회원 것: 409 — 이 경우가 곧 "이미 갈라진" 상태이고 합치는 것은 merge라 범위 밖이다
     * - 내게 그 provider가 이미 있음: 409 — 교체는 해제가 필요한데 범위 밖이다
     * - 비어 있음: null(삽입 진행)
     *
     * 순서가 중요하다. (provider, sub) 조회를 먼저 해야 같은 소셜 계정 재요청이 멱등 성공으로 처리된다.
     */
    private fun conflictOrNull(
        userId: Long,
        provider: SocialProvider,
        providerUserId: String,
    ): AccountLinkResponse? {
        socialAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)?.let { existing ->
            if (existing.userId == userId) return getLinks(userId)
            throw CodedResponseStatusException(
                HttpStatus.CONFLICT,
                ApiErrorCodes.SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER,
                "이미 다른 계정에 연동된 소셜 계정입니다.",
            )
        }
        socialAccountRepository.findByUserIdAndProvider(userId, provider)?.let {
            throw CodedResponseStatusException(
                HttpStatus.CONFLICT,
                ApiErrorCodes.PROVIDER_ALREADY_LINKED,
                "이미 연동된 로그인 방식입니다.",
            )
        }
        return null
    }

    /** 로그인 경로가 배선된 provider만 연동 대상이다(APPLE·NAVER는 enum 예약분). 표시 순서는 enum 선언 순으로 고정한다. */
    private fun linkableProviders(): List<SocialProvider> = verifiers.keys.sortedBy { it.ordinal }

    private fun verifierOrBadRequest(provider: SocialProvider): SocialIdTokenVerifier =
        verifiers[provider]
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 로그인 방식입니다.")

    private fun reauthFailed(cause: Throwable?) = CodedResponseStatusException(
        HttpStatus.FORBIDDEN,
        ApiErrorCodes.REAUTH_FAILED,
        "본인 확인에 실패했습니다. 이미 연동된 방식으로 다시 로그인해 주세요.",
        cause,
    )

    private companion object {
        /** 재인증 신선도 판정의 시계 오차 허용치(초). Nimbus 기본 skew와 같은 감각으로 둔다. */
        const val CLOCK_SKEW_SECONDS = 60L
    }
}
