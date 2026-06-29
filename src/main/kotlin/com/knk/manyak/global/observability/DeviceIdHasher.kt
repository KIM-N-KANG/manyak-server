package com.knk.manyak.global.observability

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 익명 ID를 로그에 남길 수 있는 해시로 변환한다(AN-3 §8: 원본 익명 ID 로그 금지).
 * 분석에서 동일 사용자를 묶기 위해 결정적(고정) 해시를 사용하며, pepper로 추측 공격을 완화한다.
 */
@Component
class DeviceIdHasher(
    @Value("\${manyak.analytics.device-id-pepper:}")
    private val pepper: String,
) {
    fun hash(rawDeviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((pepper + rawDeviceId).toByteArray(StandardCharsets.UTF_8))
        val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return PREFIX + hex.take(HASH_LENGTH)
    }

    companion object {
        const val PREFIX = "device_hash_"
        private const val HASH_LENGTH = 16
    }
}
