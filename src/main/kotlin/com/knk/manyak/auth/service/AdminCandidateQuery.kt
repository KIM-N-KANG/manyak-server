package com.knk.manyak.auth.service

import com.knk.manyak.auth.dto.AdminCandidate
import com.knk.manyak.auth.dto.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 관리자 인증 필터와 관리자 로그인이 회원을 판정할 때 쓰는 조회(KNK-2056). 관리자 역할은 adminkit이 따로 본다.
 *
 * 트랜잭션을 새로 정하지 않고 조회 예외를 바꾸지 않는다. 관리자 필터는 저장소 예외(`DataAccessException`)를 받아 500으로 응답한다.
 */
@Service
class AdminCandidateQuery(
    private val users: UserRepository,
    private val socials: SocialAccountRepository,
) {
    /** 관리자 토큰 주체의 판정 값. 회원이 없으면 null이다. */
    fun byPublicId(publicId: UUID): AdminCandidate? = users.findByPublicId(publicId)?.toAdminCandidate()

    /** 끊기지 않은 소셜 연동으로 찾은 회원의 판정 값. 연동 행 조회와 회원 조회를 한 번씩 한다. */
    fun bySocial(provider: SocialProvider, providerUserId: String): AdminCandidate? =
        socials.findByProviderAndProviderUserIdAndDeletedAtIsNull(provider, providerUserId)
            ?.let { users.findById(it.userId).orElse(null) }?.toAdminCandidate()

    private fun User.toAdminCandidate() = AdminCandidate(id, publicId, status, nickname, createdAt)
}
