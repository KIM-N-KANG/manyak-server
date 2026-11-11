package com.knk.manyak.auth.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.analytics.UserPublicIdPort
import org.springframework.stereotype.Component
import java.util.UUID

/** 분석 이벤트용 회원 public_id 조회 포트의 구현(KNK-2068). 조회는 전과 같은 `findById` 한 번이다. */
@Component
class UserPublicIdAdapter(private val userRepository: UserRepository) : UserPublicIdPort {

    override fun publicIdOf(userId: Long): UUID? = userRepository.findById(userId).map { it.publicId }.orElse(null)
}
