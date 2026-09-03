package com.knk.manyak.push.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.security.requireActiveStatus
import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.entity.PushPlatform
import com.knk.manyak.push.repository.DevicePushTokenRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 디바이스 푸시 토큰 등록·삭제(KNK-1131). 발송은 여기 없다(KNK-1130).
 *
 * 두 경로 모두 사용자 행을 **잠그고** 상태를 재검사한다(Codex 2차 리뷰 P1). 잠금 없이 읽으면 탈퇴가 사용자 행을
 * 잠그고 토큰을 지운 뒤 커밋해도, 대기하던 등록이 DELETED 회원에게 새 토큰을 남긴다 — 탈퇴한 회원의 기기로
 * 알림이 간다. 잠금 순서는 users → device_push_tokens 한 방향이라 탈퇴(users → social_accounts·
 * device_push_tokens)와 교차 대기 고리가 생기지 않는다.
 */
@Service
class DevicePushTokenService(
    private val devicePushTokenRepository: DevicePushTokenRepository,
    private val userRepository: UserRepository,
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
        requireActiveUser(userId)
        // 토큰 행도 잠그고 읽는다 — 커밋 전 unregister가 지운 행을 읽으면 UPDATE가 0건이 되어 500이 난다.
        val existing = devicePushTokenRepository.findByTokenForUpdate(token)
        if (existing == null) {
            evictOldestOverCap(userId)
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
        requireActiveUser(userId)
        devicePushTokenRepository.deleteByUserIdAndToken(userId, token)
    }

    /**
     * 새 기기를 넣기 전에 상한을 맞춘다(Codex 3차 리뷰 P1). 회원당 토큰이 무한히 쌓이면 발송이 그 수만큼 동기
     * FCM 호출을 낸다. 가장 오래 갱신되지 않은 것부터 지워 [MAX_DEVICES_PER_USER] - 1개로 줄인 뒤 삽입하므로,
     * 상한을 넘겨 등록하면 가장 안 쓰던 기기가 빠진다. 재등록(기존 행 갱신)은 개수가 늘지 않아 대상이 아니다.
     */
    private fun evictOldestOverCap(userId: Long) {
        val existing = devicePushTokenRepository.findAllByUserIdOrderByUpdatedAtAsc(userId)
        if (existing.size < MAX_DEVICES_PER_USER) return
        devicePushTokenRepository.deleteAll(existing.take(existing.size - MAX_DEVICES_PER_USER + 1))
    }

    /**
     * 사용자 행을 비관적 쓰기 락으로 잡고 상태를 재검사한다(스펙 §4-5 B20, KNK-499·1019 선례).
     * 정지 회원의 기기가 알림을 계속 받아서도, 탈퇴와의 경합이 DELETED 회원에게 토큰을 남겨서도 안 된다.
     */
    private fun requireActiveUser(userId: Long) {
        val user = userRepository.findByIdForUpdate(userId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        requireActiveStatus(user.status)
    }

    companion object {
        /**
         * 회원당 등록 기기 상한. 발송 경로도 같은 수만큼만 읽으므로
         * (`DevicePushTokenRepository.findTop10ByUserIdOrderByUpdatedAtDesc`, KNK-1130) **리포지토리 메서드명과
         * 함께 바꿔야 한다** — 파생 메서드명에는 상수를 쓸 수 없다.
         */
        const val MAX_DEVICES_PER_USER = 10
    }
}
