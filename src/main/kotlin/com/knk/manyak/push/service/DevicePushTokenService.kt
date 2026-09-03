package com.knk.manyak.push.service

import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.entity.PushPlatform
import com.knk.manyak.push.repository.DevicePushTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 디바이스 푸시 토큰 등록·삭제(KNK-1131). 발송은 여기 없다(KNK-1130).
 */
@Service
class DevicePushTokenService(
    private val devicePushTokenRepository: DevicePushTokenRepository,
) {

    /**
     * 등록은 upsert다. 같은 토큰이면 소유자·플랫폼·갱신 시각만 덮는다(멱등). 다른 회원이 같은 토큰을 보내면
     * 한 기기에서 계정을 바꾼 것이므로 소유자를 옮긴다 — 옛 회원의 알림이 그 기기로 가면 안 된다.
     *
     * ponytail: 같은 새 토큰의 동시 첫 등록은 유니크 위반으로 한쪽이 500을 받는다. 앱이 토큰 발급·갱신 때 한 번
     * 호출하는 경로라 실제로 겹치지 않고, 겹쳐도 중복 행은 생기지 않는다(V72 유니크). 재시도가 필요해지면
     * PostgreSQL ON CONFLICT upsert로 바꾼다(H2 테스트 프로파일이 DO UPDATE를 못 받아 지금은 피한다).
     */
    @Transactional
    fun register(userId: Long, token: String, platform: PushPlatform) {
        val existing = devicePushTokenRepository.findByToken(token)
        if (existing == null) {
            devicePushTokenRepository.save(DevicePushToken(userId = userId, token = token, platform = platform))
            return
        }
        existing.userId = userId
        existing.platform = platform
        // 값이 그대로면 dirty checking이 UPDATE를 내지 않아 @PreUpdate도 돌지 않는다. "마지막 등록 시각"은
        // 재등록마다 갱신돼야 하므로(오래된 토큰 정리 근거) 직접 찍는다.
        existing.updatedAt = Instant.now()
    }

    /** 요청자 소유 토큰만 지운다. 없거나 남의 토큰이면 조용히 0건이다(멱등). */
    @Transactional
    fun unregister(userId: Long, token: String) {
        devicePushTokenRepository.deleteByUserIdAndToken(userId, token)
    }
}
