package com.knk.manyak.auth.social

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 소셜 계정의 조회·생성(영속성) 책임만 가진다. provider는 인자로 받는다(흐름은 provider 무관 — 스펙 §4-5).
 *
 * [SocialLoginService]와 분리된 별도 빈으로 두어 트랜잭션 프록시 경계를 확보한다(self-invocation 우회 방지).
 * 특히 [createUserAndAccount]는 독립 트랜잭션([Propagation.REQUIRES_NEW])이라,
 * 동시 첫 로그인이 유니크 위반(신규 가입) 또는 claim 경합(재가입 — KNK-1053)으로 실패하면
 * **이 내부 트랜잭션만 rollback-only**가 되고 바깥(로그인) 트랜잭션은 멀쩡히 재조회를 이어갈 수 있다.
 */
@Component
class SocialAccountRegistrar(
    private val userRepository: UserRepository,
    private val socialAccountRepository: SocialAccountRepository,
    private val uniqueNicknameIssuer: UniqueNicknameIssuer,
    private val profileImagePresetService: ProfileImagePresetService,
) {

    /**
     * ([provider], providerUserId) 연동을 찾는다. 조회 키에 provider가 들어가므로 같은 `sub` 문자열이라도
     * provider가 다르면 별개 계정이다(계정 통합 미도입 — 스펙 §4-5 결정 기록).
     * - 있으면: `lastLoginAt`을 [now]로 갱신하고 연결된 [User]를 반환한다.
     * - 없으면: null.
     * - 연동은 있는데 [User]가 사라진 비정상 상태: 401(존재 여부를 노출하지 않도록 통일).
     *
     * 계약은 "**살아 있는** 연동의 **살아 있는** 계정을 찾으면 그 User"다. 어긋나면 null을 돌려 바깥이
     * [createUserAndAccount] 재가입 경로를 타게 한다. 어기고 DELETED 계정을 돌려주면 토큰이 발급돼
     * **로그인은 200인데 이후 모든 요청이 401**인 좀비 세션이 된다. 두 지점에서 확인한다.
     * 1. 갱신 0행 = 조회 이후 tombstone이 됐다(Codex 3차 리뷰 P2).
     * 2. 로드한 User가 DELETED = 갱신 이후 탈퇴가 커밋됐다(Codex 5차 리뷰 P2). 갱신 다음의 조회는 새 statement라
     *    READ COMMITTED에서 그 커밋이 보인다.
     *
     * **남는 창**: 이 조회 이후 토큰 발급 전에 탈퇴가 커밋되면 여전히 좀비 세션이다. 락으로 닫을 수 있지만
     * 그러면 이 경로가 `social_accounts`(조건부 UPDATE) → `users`(FOR UPDATE) 순서가 되어 **탈퇴와 정확히 반대**다
     * (탈퇴는 users → social_accounts). 전 경로가 `users → (wallets · social_accounts)` 단방향이라는 불변이 깨지고,
     * 가장 뜨거운 인증 경로에 비관적 락을 상시로 다는 대가가 남은 창의 크기에 비해 크다. 결과도 데이터 손상이
     * 아니라 "거부되는 토큰"이라 도입하지 않았다.
     */
    @Transactional
    fun findExistingUser(provider: SocialProvider, info: SocialUserInfo, now: Instant): User? {
        // 살아 있는 연동만 본다. tombstone(탈퇴로 끊긴 연동)이 매칭되면 탈퇴 계정으로 로그인된다(KNK-1053).
        val social = socialAccountRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull(
            provider,
            info.providerUserId,
        ) ?: return null

        val ownerId = social.userId
        // 엔티티에 직접 대입하지 않는다(KNK-1053, Codex 재리뷰 P2) — dirty checking UPDATE는 전 컬럼을 덮어
        // 동시에 커밋된 탈퇴의 tombstone·이메일 파기를 되돌린다. 조건부 단일 컬럼 갱신으로 바꿨다.
        // 0행이면 위 조회 이후 탈퇴가 커밋된 것이다. User를 조회하지도 않고 null로 빠져 재가입 경로에 맡긴다.
        if (socialAccountRepository.touchLastLoginAt(social.id, now) == 0) return null
        val user = userRepository.findById(ownerId).orElseThrow {
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        }
        // 갱신 이후 탈퇴가 커밋됐으면 여기서 DELETED가 보인다. 401이 아니라 null이다 — 그 소셜 신원은
        // 여전히 로그인에 쓸 수 있고(재가입), 바깥이 tombstone claim 재가입 경로로 이어 가면 된다.
        return user.takeIf { it.status != UserStatus.DELETED }
    }

    /**
     * 신규 [User]를 만들고 [SocialAccount]를 연결한다. 탈퇴한 계정의 소셜 신원이면 **재가입**으로 처리한다.
     *
     * 재가입도 계정 부활이 아니라 **새 계정**이다(팀 결정) — 탈퇴한 스토리·크레딧이 되살아나면 앱 심사에서
     * 삭제 미이행으로 읽힌다. 대신 user_id에 매달려 재가입으로 리셋되던 것들만 새 계정으로 넘긴다(KNK-1053):
     * - [User.rejoinedAt] 기록 → 회원 무료 체험 미부여 판정([com.knk.manyak.auth.social.SocialLoginService])
     * - [User.inviterUserId] 승계 → 초대 코드 평생 1회 소진 표식 이월
     * - [User.rewardIdentityUserId] 승계 → 가입·출석 등 1회성 보상 멱등 키를 최초 계정에 고정(루트 복사)
     * - [User.migrationAttempts] 승계 → 게스트 이관 시도 상한(B19)이 사람 단위 열거 예산으로 남게 한다
     * - [User.status] 승계 → 정지(SUSPENDED) 제재가 탈퇴·재가입 한 번으로 무력화되지 않게 한다.
     *   탈퇴 자체는 계속 허용한다(앱 심사 요건 — 정지 회원이라고 계정 삭제를 막을 수 없다). 대신 새 계정이 제재를 물려받는다.
     * 소셜 행은 tombstone을 **재사용**한다. `(provider, provider_user_id)` 유니크가 새 행 insert를 막으므로
     * DB가 이 재사용을 강제하며, 그래서 우회 경로가 생기지 않는다.
     *
     * 재가입은 이전 소유자의 **tombstone을 전부** 새 계정으로 옮긴다(Codex 리뷰 P2). 로그인한 provider의 행 하나만
     * 옮기면, 두 provider를 연동했던 계정이 탈퇴한 뒤 나머지 행이 계속 옛 소유자를 가리켜 재가입 이후에 쌓인 표식
     * (초대 소진·이관 시도·정지)이 형제에게 보이지 않는다 — 다른 provider로 로그인하면 표식 없는 또 하나의 ACTIVE
     * 계정이 생긴다. 묶음째 옮기면 탈퇴 전 연동 구성이 재가입 계정에 복원되고, 이후 다른 provider 로그인은
     * tombstone이 아니라 **살아 있는 연동**을 만나 [findExistingUser]가 같은 계정을 돌려준다.
     *
     * 독립 트랜잭션([Propagation.REQUIRES_NEW]): 동시 요청이 같은 계정을 둘 다 insert하면
     * 한쪽이 `social_accounts (provider, provider_user_id)` 유니크 위반으로 실패하는데,
     * 그 실패(rollback-only)를 이 트랜잭션 안에 가둬 바깥 로그인 트랜잭션이 재조회로 복구할 수 있게 한다.
     *
     * **재가입 경합은 유니크가 못 막는다**(KNK-1053): claim은 insert가 아니라 기존 행 UPDATE라, 동시 요청 둘이
     * 같은 tombstone을 갱신해도 뒤가 앞을 덮어쓸 뿐 위반이 나지 않는다. 그러면 양쪽 다 커밋해 `User`가 둘 생기고
     * 먼저 커밋한 쪽은 소셜 행 없는 orphan이 되는데 토큰은 이미 나간 뒤다.
     *
     * 그래서 **이전 소유자의 `users` 행 하나**를 비관적 락으로 잡아 그 소유자의 tombstone 이동 전체를 직렬화한다.
     * 소셜 행들을 각각 잠그지 않는 이유는 순서 때문이다 — 요청된 행부터 잠그면 provider A·B로 동시에 들어온 두
     * 재가입이 서로가 쥔 행을 교차 대기해 데드락이 난다(고정 순서를 정해도 첫 락이 요청 provider라 어긋난다).
     * 소유자 행은 어느 provider로 들어오든 같은 한 행이라 순서 문제가 아예 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun createUserAndAccount(provider: SocialProvider, info: SocialUserInfo, now: Instant): User {
        // 소유자 id만 스칼라로 읽는다(엔티티를 1차 캐시에 올리지 않는다 — 아래 락 이후 조회가 최신 커밋을 봐야 한다).
        // 없으면 순수 신규 가입이라 잠글 대상도 없다(경합은 종전대로 유니크가 막는다).
        val previousOwner = socialAccountRepository.findOwnerUserId(provider, info.providerUserId)
            ?.let { ownerId ->
                // 이전 소유자 행을 잠가 이 소유자의 tombstone 이동을 직렬화한다. 잠근 뒤 처음 읽어야
                // 상대 요청이 이미 옮겼는지를 정확히 본다.
                userRepository.findByIdForUpdate(ownerId) ?: throw DataIntegrityViolationException(
                    "소셜 연동이 가리키는 회원이 없습니다: userId=$ownerId",
                )
            }
        // 이 소유자의 연동 전부(tombstone 포함). 요청된 행이 여기 없거나 이미 살아 있으면, 조회 이후 상대 요청이
        // 먼저 옮겼거나 누가 연동한 것이다. User를 만들기 전에 끊어야 orphan이 남지 않는다.
        val bundle = previousOwner?.let { owner -> socialAccountRepository.findByUserId(owner.id) }.orEmpty()
        val tombstone = if (previousOwner == null) {
            null
        } else {
            bundle.firstOrNull { it.provider == provider && it.providerUserId == info.providerUserId }
                ?.takeIf { it.deletedAt != null }
                ?: throw DataIntegrityViolationException("이미 사용 중인 소셜 연동입니다: provider=$provider")
        }
        // 실명·외부 사진 노출을 피하기 위해 소셜 `name`·`picture` 대신 랜덤 닉네임과 프리셋 이미지를 발급한다(스펙 §4-5, B7).
        // 정규화 기준 유일해야 한다(V75). 충돌하면 다시 뽑고, 계속 부딪히면 접미를 붙인다(KNK-1147).
        val nickname = uniqueNicknameIssuer.issue()
        val user = userRepository.save(
            User(
                nickname = nickname.text,
                // 닉네임 명사에 1:1 매핑된 팀 제작 프리셋을 배정한다(KNK-388). 매핑 없으면 null → 클라이언트 기본 아바타.
                profileImageUrl = profileImagePresetService.imageUrlFor(nickname.noun),
                profileThumbnailBase64 = profileImagePresetService.thumbnailBase64For(nickname.noun),
                // 정지 제재는 재가입을 관통한다. 탈퇴가 status를 DELETED로 덮어쓰므로 판정은 탈퇴 시 기록한
                // withdrawnFromStatus로 한다(아직 살아 있는 행을 claim하는 경로까지 덮도록 status도 함께 본다).
                status = if (previousOwner.wasSuspended()) UserStatus.SUSPENDED else UserStatus.ACTIVE,
                // 이전 소유자가 초대 코드를 이미 제출했다면 그 소진을 승계한다(재가입으로 자격이 부활하지 않게).
                inviterUserId = previousOwner?.inviterUserId,
                // 1회성 보상 멱등 키를 최초 계정에 고정한다. 체인이 아니라 **루트를 복사**해 재가입을 반복해도 항상 최초를 가리킨다.
                rewardIdentityUserId = previousOwner?.let { it.rewardIdentityUserId ?: it.id },
                // 이관 시도 횟수는 **사람 단위 열거 예산**(B19, KNK-500)이라 승계한다 — 리셋되면 탈퇴·재가입 반복으로
                // 소유 상태 열거가 사실상 무제한이 된다. 반면 `migratedAt`(계정 단위 이관 잠금)은 승계하지 않는다:
                // 재가입자가 새로 만든 게스트 콘텐츠를 가져갈 길까지 막을 이유가 없고, 이관은 크레딧을 주지 않아 파밍 실익도 없다.
                migrationAttempts = previousOwner?.migrationAttempts ?: 0,
                rejoinedAt = now.takeIf { tombstone != null },
            ),
        )
        if (tombstone == null) {
            socialAccountRepository.save(
                SocialAccount(
                    userId = user.id,
                    provider = provider,
                    providerUserId = info.providerUserId,
                    email = info.email,
                    connectedAt = now,
                    lastLoginAt = now,
                ),
            )
        } else {
            tombstone.email = info.email
            tombstone.connectedAt = now
            // 이번 로그인은 이 provider로 들어왔다. 함께 옮기는 형제 행은 로그인한 게 아니라 lastLoginAt·email을 두지 않는다.
            tombstone.lastLoginAt = now
            // 묶음째 이동: 이전 소유자의 tombstone을 전부 새 계정으로 옮겨 연동 구성을 복원한다.
            // (소유자가 DELETED라 살아 있는 행은 없지만, 방어적으로 tombstone만 고른다.)
            bundle.filter { it.deletedAt != null }.forEach { social ->
                social.userId = user.id
                social.deletedAt = null
            }
        }
        return user
    }

    /** 정지 제재 승계 판정(KNK-1053). 탈퇴가 [User.status]를 덮어쓰므로 탈퇴 직전 상태까지 함께 본다. */
    private fun User?.wasSuspended(): Boolean =
        this?.status == UserStatus.SUSPENDED || this?.withdrawnFromStatus == UserStatus.SUSPENDED
}
