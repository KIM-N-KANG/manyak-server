package com.knk.manyak.auth.social

import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.AuthTokenService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * Google ID 토큰으로 로그인한다(검증 → find-or-create → 우리 토큰 발급).
 *
 * 1) [GoogleIdTokenVerifier]로 ID 토큰을 검증해 [SocialUserInfo]를 얻는다(실패는 401).
 * 2) (GOOGLE, providerUserId)로 [SocialAccount]를 찾는다.
 *    - 있으면: 연결된 [User]를 재사용하고 `lastLoginAt`을 갱신한다.
 *    - 없으면: [User](nickname=name→email local-part→"사용자", profileImageUrl=picture, ACTIVE)와
 *      [SocialAccount]를 생성한다.
 * 3) [AuthTokenService.issueTokens]로 access+refresh를 발급해 반환한다.
 *
 * @Transactional로 묶어, 생성 도중 실패 시 부분 저장이 남지 않게 한다.
 */
@Service
class GoogleLoginService(
    private val verifier: GoogleIdTokenVerifier,
    private val userRepository: UserRepository,
    private val socialAccountRepository: SocialAccountRepository,
    private val authTokenService: AuthTokenService,
) {

    @Transactional
    fun login(idToken: String): TokenResponse {
        val info = verifier.verify(idToken)
        val now = Instant.now()

        val existing = socialAccountRepository.findByProviderAndProviderUserId(
            SocialProvider.GOOGLE,
            info.providerUserId,
        )

        val user = if (existing != null) {
            // 기존 연동: 사용자를 재사용하고 마지막 로그인 시각만 갱신한다.
            existing.lastLoginAt = now
            userRepository.findById(existing.userId).orElseThrow {
                // 연동은 있는데 사용자가 사라진 비정상 상태. 존재 여부를 노출하지 않도록 401로 통일한다.
                ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
            }
        } else {
            // 신규 연동: 사용자와 소셜 계정을 함께 생성한다.
            val created = userRepository.save(
                User(
                    nickname = resolveNickname(info),
                    profileImageUrl = info.picture,
                    status = UserStatus.ACTIVE,
                ),
            )
            socialAccountRepository.save(
                SocialAccount(
                    userId = created.id,
                    provider = SocialProvider.GOOGLE,
                    providerUserId = info.providerUserId,
                    email = info.email,
                    connectedAt = now,
                    lastLoginAt = now,
                ),
            )
            created
        }

        return authTokenService.issueTokens(user)
    }

    /** 닉네임 우선순위: 프로필 이름 → 이메일 local-part → 기본값. 빈 문자열은 없는 것으로 본다. */
    private fun resolveNickname(info: SocialUserInfo): String =
        info.name?.takeIf { it.isNotBlank() }
            ?: info.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NICKNAME

    private companion object {
        const val DEFAULT_NICKNAME = "사용자"
    }
}
