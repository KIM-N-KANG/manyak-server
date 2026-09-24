package com.knk.manyak.story.service

import com.knk.manyak.auth.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/** 오리지널 필터와 모든 스토리 카드가 공유하는 공식 계정 조회. 요청마다 한 번 조회해 재사용한다. */
@Component
class OfficialStoryAccount(
    private val userRepository: UserRepository,
    @Value("\${manyak.official-user-public-id:}") officialUserPublicId: String,
) {
    // 잘못된 UUID는 기존 목록과 같이 기동 시점에 실패시킨다.
    private val officialUserPublicId: UUID? =
        officialUserPublicId.takeIf { it.isNotBlank() }?.let(UUID::fromString)

    /** 설정이 비었거나 해당 publicId의 회원이 없으면 null이다. */
    fun officialUserId(): Long? =
        officialUserPublicId?.let { userRepository.findByPublicId(it) }?.id
}
