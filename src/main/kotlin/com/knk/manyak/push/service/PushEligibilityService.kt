package com.knk.manyak.push.service

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.dto.PushEligibilityResponse
import com.knk.manyak.push.dto.PushEligibilityToken
import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.repository.DevicePushTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class PushEligibilityService(
    private val userRepository: UserRepository,
    private val devicePushTokenRepository: DevicePushTokenRepository,
) {
    @Transactional(readOnly = true)
    fun getEligibility(publicId: UUID, kind: PushKind, at: Instant): PushEligibilityResponse {
        val user = userRepository.findByPublicId(publicId)
            ?: return PushEligibilityResponse(allowed = false, reason = "USER_NOT_FOUND")
        if (user.status != UserStatus.ACTIVE) {
            return PushEligibilityResponse(allowed = false, reason = "NOT_ACTIVE")
        }
        if (kind == PushKind.SERVICE && !user.servicePushEnabled) {
            return PushEligibilityResponse(allowed = false, reason = "SERVICE_PUSH_DISABLED")
        }
        if (kind == PushKind.MARKETING && !user.canReceiveMarketingPush(at)) {
            return PushEligibilityResponse(allowed = false, reason = "MARKETING_NOT_AGREED")
        }
        // 거절된 회원의 기기 주소를 조회하거나 응답에 노출하지 않는다.
        val tokens = devicePushTokenRepository.findTop10ByUserIdOrderByUpdatedAtDesc(user.id)
            .map { PushEligibilityToken(token = it.token, platform = it.platform) }
        if (tokens.isEmpty()) {
            return PushEligibilityResponse(allowed = false, reason = "NO_TOKENS")
        }
        return PushEligibilityResponse(allowed = true, reason = "OK", tokens = tokens)
    }
}
