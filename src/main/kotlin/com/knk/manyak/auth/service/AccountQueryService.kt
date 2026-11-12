package com.knk.manyak.auth.service

import com.knk.manyak.auth.dto.AccountProfile
import com.knk.manyak.auth.dto.AccountStatusView
import com.knk.manyak.auth.dto.PushRecipient
import com.knk.manyak.auth.dto.AdminMemberView
import com.knk.manyak.auth.dto.InviteAccount
import com.knk.manyak.auth.dto.MigrationState
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.analytics.UserPublicIdPort
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 다른 모듈이 계정 값을 읽는 조회 서비스(KNK-2056). 엔티티가 아니라 호출자가 쓰는 값만 돌려준다.
 *
 * 트랜잭션을 새로 정하지 않는다. 리포지토리 호출이 호출자의 트랜잭션에 참여하거나 없으면 자기 읽기 트랜잭션을 쓰는 것은
 * 호출자가 리포지토리를 직접 부르던 때와 같다. 메서드마다 쿼리는 한 번이고 바꾸기 전과 같은 쿼리다.
 */
@Service
class AccountQueryService(private val users: UserRepository) : UserPublicIdPort {

    /** 내부 id의 공개 식별자. 회원이 없으면 null이다. */
    override fun publicIdOf(userId: Long): UUID? = users.findById(userId).map { it.publicId }.orElse(null)

    /** [publicIdOf]와 같되 회원이 없으면 `Optional.orElseThrow`와 같은 [NoSuchElementException]을 던진다. */
    fun requirePublicIdOf(userId: Long): UUID = users.findById(userId).orElseThrow().publicId

    /**
     * 내부 id 묶음의 공개 식별자. [PUBLIC_ID_BATCH_SIZE]개씩 나눠 조회하고, 빈 입력은 조회하지 않는다.
     * 회원이 하나라도 없으면 [requirePublicIdOf]와 같은 [NoSuchElementException]을 던진다.
     */
    fun requirePublicIdsOf(userIds: Collection<Long>): Map<Long, UUID> {
        val ids = userIds.distinct()
        val found = ids.chunked(PUBLIC_ID_BATCH_SIZE).flatMap(users::findPublicIdsByIdIn).associate { it.id to it.publicId }
        if (found.size != ids.size) throw NoSuchElementException("No value present")
        return found
    }

    /** 공개 식별자의 내부 id. 회원이 없으면 null이며 계정 상태는 보지 않는다. */
    fun userIdOf(publicId: UUID): Long? = users.findByPublicId(publicId)?.id

    fun profileOf(userId: Long): AccountProfile? = users.findById(userId).map { it.toProfile() }.orElse(null)

    /** [profileOf]와 같되 회원이 없으면 `Optional.orElseThrow`와 같은 [NoSuchElementException]을 던진다. */
    fun requireProfileOf(userId: Long): AccountProfile = users.findById(userId).orElseThrow().toProfile()

    /** 회원의 내부 id, 공개 식별자, 상태. 회원이 없으면 null이며 판정은 호출부가 한다. */
    fun statusOf(userId: Long): AccountStatusView? =
        users.findById(userId).map { AccountStatusView(it.id, it.publicId, it.status) }.orElse(null)

    /** 푸시 발송 자격 판정용 값. 회원이 없으면 null이다. */
    fun pushRecipientOf(userId: Long): PushRecipient? = users.findById(userId).map { it.toPushRecipient() }.orElse(null)

    fun pushRecipientOf(publicId: UUID): PushRecipient? = users.findByPublicId(publicId)?.toPushRecipient()

    /** 회원 묶음의 프로필. 없는 회원은 결과에 없다. 빈 입력은 조회하지 않는다. */
    fun profilesOf(userIds: Collection<Long>): Map<Long, AccountProfile> {
        if (userIds.isEmpty()) return emptyMap()
        return users.findAllById(userIds.distinct()).associate { it.id to it.toProfile() }
    }

    /** 1회성 보상 멱등 키의 신원(`coalesce(reward_identity_user_id, id)`, KNK-1053). 회원이 없으면 [userId] 그대로다. */
    fun rewardIdentityOf(userId: Long): Long = users.findRewardIdentityUserId(userId) ?: userId

    /** 초대 발급과 제출이 읽는 회원 값. 잠근 회원이면 영속성 컨텍스트에서 나와 SQL을 내지 않는다. */
    fun inviteAccountOf(userId: Long): InviteAccount? = users.findById(userId).map { it.toInviteAccount() }.orElse(null)

    /** 초대 코드의 주인. 잠그지 않는다. 없으면 null이다. */
    fun inviteAccountByCode(inviteCode: String): InviteAccount? = users.findByInviteCode(inviteCode)?.toInviteAccount()

    /** 초대 코드가 이미 쓰이는지. 유니크 제약이 최종 방어이고 이건 재시도용 사전 점검이다. */
    fun isInviteCodeTaken(inviteCode: String): Boolean = users.existsByInviteCode(inviteCode)

    /** 이관 게이트가 읽는 값. 잠근 회원이면 영속성 컨텍스트에서 나와 SQL을 내지 않는다. */
    fun migrationStateOf(userId: Long): MigrationState? =
        users.findById(userId).map { MigrationState(it.migratedAt, it.migrationAttempts) }.orElse(null)

    fun adminMemberOf(publicId: UUID): AdminMemberView? =
        users.findByPublicId(publicId)?.let { AdminMemberView(it.id, it.publicId, it.deletedAt) }

    companion object {
        /** 묶음 조회의 IN 목록 상한. 검색 색인과 푸시 도달 조회의 묶음 크기(500)와 같다. */
        const val PUBLIC_ID_BATCH_SIZE = 500
    }

    private fun User.toProfile() = AccountProfile(publicId, nickname, profileImageUrl)

    private fun User.toInviteAccount() =
        InviteAccount(id, publicId, status, inviteCode, inviterUserId, rewardIdentityUserId ?: id)

    private fun User.toPushRecipient() =
        PushRecipient(id, publicId, status, servicePushEnabled, marketingPushAgreedAt, marketingPushNightAgreedAt)
}
