package com.knk.manyak.user.service

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.RefreshTokenStore
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * 회원 탈퇴(KNK-1019, 스펙 §4-3-5). 앱 심사 요건.
 *
 * - soft delete: `users` 행은 남기고 status만 DELETED로 전환한다(FK·집계·스토리 작성자 참조 보존).
 * - 개인정보 파기(2026-08-30 팀 결정): 닉네임 익명화 + 프로필 이미지 제거 + 소셜 연결(social_accounts) 삭제.
 *   같은 소셜 계정으로 재가입하면 새 계정이 만들어진다.
 * - 소유 스토리는 공개 상태를 유지한다(팀 결정). 작성자 표기는 익명화된 닉네임이 자연 반영된다.
 * - refresh는 전 family 폐기, 잔여 access 토큰은 해석 계층(CurrentUserIdArgumentResolver)이 전면 401로 무효화한다.
 */
@Service
class UserWithdrawalService(
    private val userRepository: UserRepository,
    private val socialAccountRepository: SocialAccountRepository,
    private val refreshTokenStore: RefreshTokenStore,
) {

    @Transactional
    fun withdraw(userId: Long?) {
        val id = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        val user = userRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        }
        // 재탈퇴는 여기 오기 전에 해석 계층(CurrentUserIdArgumentResolver)이 DELETED 토큰을 401로 끊는다.
        user.status = UserStatus.DELETED
        user.deletedAt = java.time.Instant.now()
        user.nickname = ANONYMIZED_NICKNAME
        user.profileImageUrl = null
        user.profileThumbnailBase64 = null
        socialAccountRepository.deleteByUserId(id)
        refreshTokenStore.revokeAllForUser(id)
    }

    companion object {
        const val ANONYMIZED_NICKNAME = "탈퇴한 사용자"
    }
}
