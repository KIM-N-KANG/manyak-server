package com.knk.manyak.user.service

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.push.repository.DevicePushTokenRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 회원 탈퇴(KNK-1019, 스펙 §4-3-5). 앱 심사 요건.
 *
 * - soft delete: `users` 행은 남기고 status만 DELETED로 전환한다(FK·집계·스토리 작성자 참조 보존).
 * - 개인정보 파기(2026-08-30 팀 결정): 닉네임 익명화 + 프로필 이미지 제거 + 소셜 연동 이메일 삭제.
 * - 소셜 연동은 **행을 지우지 않고 tombstone으로 남긴다**(KNK-1053). 하드 삭제하면 소셜 신원 ↔ 계정 링크가
 *   사라져 같은 소셜 계정의 재가입마다 완전히 새 `users` 행이 생기고, user_id에 매달린 1회성 혜택
 *   (가입 보상·초대 제출 자격)이 전부 리셋된다. 행을 남기면 `(provider, provider_user_id)` 유니크가
 *   재가입에게 그 행의 재사용을 강제한다. 재가입은 여전히 **새 계정**이다(계정 부활 아님 — 탈퇴한
 *   스토리·크레딧이 되살아나면 앱 심사에서 삭제 미이행으로 읽힌다).
 * - 탈퇴 직전 상태를 `withdrawn_from_status`에 남긴다(KNK-1053) — 재가입 계정의 정지 제재 승계 근거.
 * - 소유 스토리는 공개 상태를 유지한다(팀 결정). 작성자 표기는 익명화된 닉네임이 자연 반영된다.
 * - refresh는 전 family 폐기, 잔여 access 토큰은 해석 계층(CurrentUserIdArgumentResolver)이 전면 401로 무효화한다.
 * - 디바이스 푸시 토큰은 하드 삭제한다(KNK-1131). 탈퇴한 회원의 기기로 알림이 가면 안 되고, 토큰은 재가입 매칭에
 *   쓰이지 않아 남길 이유가 없다(재가입 기기는 앱이 새로 등록한다).
 */
@Service
class UserWithdrawalService(
    private val userRepository: UserRepository,
    private val socialAccountRepository: SocialAccountRepository,
    private val refreshTokenStore: RefreshTokenStore,
    private val devicePushTokenRepository: DevicePushTokenRepository,
) {

    @Transactional
    fun withdraw(userId: Long?) {
        val id = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        // 사용자 행을 **잠그고** 읽는다(KNK-1053, Codex 4차 리뷰 P1). [User]에는 `@Version`도 `@DynamicUpdate`도
        // 없어 dirty checking UPDATE가 전 컬럼을 자기 스냅샷으로 덮는다. 잠그지 않으면 탈퇴가 읽은 뒤 커밋된
        // 동시 갱신이 통째로 되돌아간다 — `inviter_user_id`(초대 제출 소진)가 NULL로 복구되면 그 tombstone으로
        // 재가입한 계정이 초대 보상을 다시 받는다. 되돌아갈 수 있는 건 그 컬럼만이 아니다: `rejoined_at`·
        // `reward_identity_user_id`·`member_trial_seeded_at`·`migration_attempts`·`invite_code`까지 같은 행의
        // 모든 동시 갱신이 대상이고, 락이 그 전부를 한 번에 닫는다.
        //
        // 데드락 없음: 이 트랜잭션이 잡는 잠금은 사용자 행과 그 사용자의 `social_accounts` 행뿐이고 지갑 락은
        // 잡지 않는다. 지갑 락(KNK-587 순서 규칙)은 [com.knk.manyak.credit.service.CreditWalletService]에만 있고,
        // 그걸 잡는 경로(redeem 등)는 모두 사용자 행을 먼저 잡는다. 전 경로가 users → (wallets·social_accounts)
        // 한 방향이라 교차 대기 고리가 생기지 않는다.
        val user = userRepository.findByIdForUpdate(id)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        // 잠금 후 재검사. 해석 계층(CurrentUserIdArgumentResolver)이 DELETED 토큰을 이미 401로 끊지만, 필터 통과
        // 직후 다른 요청의 탈퇴가 커밋되는 경합에서는 이게 마지막 방어선이다. 여기서 막지 않으면 두 번째 탈퇴가
        // `withdrawnFromStatus`를 DELETED로 덮어써 "정지였다"는 승계 근거를 지운다.
        // 정지(SUSPENDED) 계정의 탈퇴는 계속 허용해야 하므로(앱 심사 요건) `requireActiveStatus`는 쓰지 않는다.
        if (user.status == UserStatus.DELETED) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        }
        val now = Instant.now()
        // 상태를 덮어쓰기 전에 남긴다. 정지 회원의 탈퇴도 허용하되(앱 심사 요건), 재가입 계정이 제재를
        // 물려받아야 하기 때문이다(KNK-1053) — DELETED로 덮고 나면 정지였다는 사실을 복원할 방법이 없다.
        user.withdrawnFromStatus = user.status
        user.status = UserStatus.DELETED
        user.deletedAt = now
        user.nickname = ANONYMIZED_NICKNAME
        user.profileImageUrl = null
        user.profileThumbnailBase64 = null
        // 이메일은 지우고 provider_user_id는 남긴다: 이메일은 그 자체로 연락 가능한 개인정보라 파기 대상이지만,
        // provider_user_id는 제공자 없이는 누구인지 알 수 없는 pseudonymous ID이고 재가입 매칭 키다.
        socialAccountRepository.findByUserId(id).forEach { social ->
            social.deletedAt = now
            social.email = null
        }
        devicePushTokenRepository.deleteByUserId(id)
        refreshTokenStore.revokeAllForUser(id)
    }

    companion object {
        const val ANONYMIZED_NICKNAME = "탈퇴한 사용자"
    }
}
