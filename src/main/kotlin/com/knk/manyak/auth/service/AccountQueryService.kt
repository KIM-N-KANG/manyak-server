package com.knk.manyak.auth.service

import com.knk.manyak.auth.dto.AccountProfile
import com.knk.manyak.auth.dto.AdminMemberView
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

    /** 공개 식별자의 내부 id. 회원이 없으면 null이며 계정 상태는 보지 않는다. */
    fun userIdOf(publicId: UUID): Long? = users.findByPublicId(publicId)?.id

    fun profileOf(userId: Long): AccountProfile? = users.findById(userId).map { it.toProfile() }.orElse(null)

    /** 회원 묶음의 프로필. 없는 회원은 결과에 없다. 빈 입력은 조회하지 않는다. */
    fun profilesOf(userIds: Collection<Long>): Map<Long, AccountProfile> {
        if (userIds.isEmpty()) return emptyMap()
        return users.findAllById(userIds.distinct()).associate { it.id to it.toProfile() }
    }

    /** 1회성 보상 멱등 키의 신원(`coalesce(reward_identity_user_id, id)`, KNK-1053). 회원이 없으면 [userId] 그대로다. */
    fun rewardIdentityOf(userId: Long): Long = users.findRewardIdentityUserId(userId) ?: userId

    fun adminMemberOf(publicId: UUID): AdminMemberView? =
        users.findByPublicId(publicId)?.let { AdminMemberView(it.id, it.publicId, it.deletedAt) }

    private fun User.toProfile() = AccountProfile(publicId, nickname, profileImageUrl)
}
