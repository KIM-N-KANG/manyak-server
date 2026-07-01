package com.knk.manyak.global.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 익명 ID 해시 규칙을 고정한다: 고정(결정적) 해시 + device_hash_ 접두 + 원본 미노출.
 * 분석에서 동일 사용자를 묶으려면 같은 입력이 항상 같은 해시여야 하고,
 * 개인정보 원칙(AN-3 §8)상 원본 익명 ID는 로그/해시 결과에 노출되면 안 된다.
 */
class DeviceIdHasherTests {

    private val hasher = DeviceIdHasher(pepper = "")

    @Test
    fun `같은 익명 ID는 항상 같은 해시로 매핑된다`() {
        val raw = "anon-12345"
        assertThat(hasher.hash(raw)).isEqualTo(hasher.hash(raw))
    }

    @Test
    fun `다른 익명 ID는 다른 해시를 만든다`() {
        assertThat(hasher.hash("anon-a")).isNotEqualTo(hasher.hash("anon-b"))
    }

    @Test
    fun `해시는 device_hash_ 접두와 고정 길이를 가진다`() {
        val hash = hasher.hash("anon-12345")
        assertThat(hash).startsWith("device_hash_")
        assertThat(hash).hasSize("device_hash_".length + 16)
    }

    @Test
    fun `원본 익명 ID를 그대로 노출하지 않는다`() {
        val raw = "super-secret-anon-id"
        assertThat(hasher.hash(raw)).doesNotContain(raw)
    }

    @Test
    fun `pepper가 다르면 같은 입력도 다른 해시가 된다`() {
        val raw = "anon-12345"
        assertThat(DeviceIdHasher(pepper = "p1").hash(raw))
            .isNotEqualTo(DeviceIdHasher(pepper = "p2").hash(raw))
    }
}
