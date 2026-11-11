package com.knk.manyak.auth.dto

import java.time.Instant
import java.util.UUID

/** 다른 모듈이 작성자 카드와 소유자 요약에 쓰는 회원 프로필. [com.knk.manyak.auth.service.AccountQueryService]가 돌려준다. */
data class AccountProfile(val publicId: UUID, val nickname: String, val profileImageUrl: String?)

/** 관리자 회원 조회가 public_id로 찾은 회원의 내부 id와 탈퇴 시각. */
data class AdminMemberView(val id: Long, val publicId: UUID, val deletedAt: Instant?)

/** 계정 상태 확인에 쓰는 회원의 내부 id, 공개 식별자, 상태. */
data class AccountStatusView(val id: Long, val publicId: UUID, val status: UserStatus)

/**
 * 푸시 발송 자격 판정에 쓰는 회원 값. 수신 동의 세 컬럼은 NOTIFICATION이 정하는 컬럼이고(KNK-1132, V73),
 * 광고 야간 판정은 NOTIFICATION 규칙이라 push가 한다.
 */
data class PushRecipient(
    val id: Long,
    val publicId: UUID,
    val status: UserStatus,
    val servicePushEnabled: Boolean,
    val marketingPushAgreedAt: Instant?,
    val marketingPushNightAgreedAt: Instant?,
)

/**
 * 초대 코드 발급과 제출이 읽는 회원 값(KNK-2056). [rewardIdentity]는 1회성 보상 멱등 키의 신원
 * (`coalesce(reward_identity_user_id, id)`, KNK-1053)이다.
 */
data class InviteAccount(
    val id: Long,
    val publicId: UUID,
    val status: UserStatus,
    val inviteCode: String?,
    val inviterUserId: Long?,
    val rewardIdentity: Long,
)

/** 게스트 데이터 이관 게이트가 읽는 회원 값(이관 완료 시각, 시도 수). */
data class MigrationState(val migratedAt: Instant?, val migrationAttempts: Int)
