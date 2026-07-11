package com.knk.manyak.auth.social

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * Google 소셜 계정의 조회·생성(영속성) 책임만 가진다.
 *
 * [GoogleLoginService]와 분리된 별도 빈으로 두어 트랜잭션 프록시 경계를 확보한다(self-invocation 우회 방지).
 * 특히 [createUserAndAccount]는 독립 트랜잭션([Propagation.REQUIRES_NEW])이라,
 * 동시 첫 로그인으로 유니크 위반이 나면 **이 내부 트랜잭션만 rollback-only**가 되고
 * 바깥(로그인) 트랜잭션은 멀쩡히 재조회를 이어갈 수 있다.
 */
@Component
class GoogleAccountRegistrar(
    private val userRepository: UserRepository,
    private val socialAccountRepository: SocialAccountRepository,
    private val nicknameGenerator: NicknameGenerator,
    private val profileImagePresetService: ProfileImagePresetService,
) {

    /**
     * (GOOGLE, providerUserId) 연동을 찾는다.
     * - 있으면: `lastLoginAt`을 [now]로 갱신하고 연결된 [User]를 반환한다.
     * - 없으면: null.
     * - 연동은 있는데 [User]가 사라진 비정상 상태: 401(존재 여부를 노출하지 않도록 통일).
     */
    @Transactional
    fun findExistingUser(info: SocialUserInfo, now: Instant): User? {
        val social = socialAccountRepository.findByProviderAndProviderUserId(
            SocialProvider.GOOGLE,
            info.providerUserId,
        ) ?: return null

        social.lastLoginAt = now
        return userRepository.findById(social.userId).orElseThrow {
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        }
    }

    /**
     * 신규 [User]와 [SocialAccount]를 함께 생성한다.
     *
     * 독립 트랜잭션([Propagation.REQUIRES_NEW]): 동시 요청이 같은 계정을 둘 다 insert하면
     * 한쪽이 `social_accounts (provider, provider_user_id)` 유니크 위반으로 실패하는데,
     * 그 실패(rollback-only)를 이 트랜잭션 안에 가둬 바깥 로그인 트랜잭션이 재조회로 복구할 수 있게 한다.
     *
     * [inviterUserId]가 있으면 초대자 관계를 이 생성 트랜잭션에 함께 커밋한다(KNK-393). 계정만 커밋되고
     * 초대 보상 적립 전에 실패해도, 관계가 계정과 원자적으로 남아 다음 로그인이 보상을 자가 복구할 수 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun createUserAndAccount(info: SocialUserInfo, now: Instant, inviterUserId: Long?): User {
        // 실명·외부 사진 노출을 피하기 위해 Google `name`·`picture` 대신 랜덤 닉네임과 프리셋 이미지를 발급한다(스펙 §4-5, B7).
        val nickname = nicknameGenerator.generate()
        val user = userRepository.save(
            User(
                nickname = nickname.text,
                // 닉네임 명사에 1:1 매핑된 팀 제작 프리셋을 배정한다(KNK-388). 매핑 없으면 null → 클라이언트 기본 아바타.
                profileImageUrl = profileImagePresetService.imageUrlFor(nickname.noun),
                profileThumbnailBase64 = profileImagePresetService.thumbnailBase64For(nickname.noun),
                status = UserStatus.ACTIVE,
                inviterUserId = inviterUserId,
            ),
        )
        socialAccountRepository.save(
            SocialAccount(
                userId = user.id,
                provider = SocialProvider.GOOGLE,
                providerUserId = info.providerUserId,
                email = info.email,
                connectedAt = now,
                lastLoginAt = now,
            ),
        )
        return user
    }
}
