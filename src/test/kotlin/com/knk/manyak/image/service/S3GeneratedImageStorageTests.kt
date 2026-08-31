package com.knk.manyak.image.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * 미구성·반쪽 구성에서 no-op으로 떨어지는지 고정한다(KNK-966).
 *
 * 버킷과 전용 base URL 중 하나만 있으면 URL을 만들 수 없다. 그 상태에서 업로드가 성공한 척하면 잘못된 URL이
 * DB에 굳으므로, 아예 S3 클라이언트를 만들지 않고 null을 돌려 이미지 없이 저장되게 한다. AWS 자격증명이 없는
 * 환경에서도 이 경로가 예외 없이 도는지가 이 테스트의 요지다.
 */
class S3GeneratedImageStorageTests {

    @Test
    fun `버킷과 base URL이 모두 비면 업로드가 no-op이다`() {
        val storage = storage(bucket = "", baseUrl = "")

        assertThat(storage.upload("characters/generated/story/image.webp", BYTES, "image/webp")).isNull()
    }

    @Test
    fun `버킷만 있고 base URL이 없으면 업로드가 no-op이다`() {
        val storage = storage(bucket = "manyak-character-images", baseUrl = "")

        assertThat(storage.upload("characters/generated/story/image.webp", BYTES, "image/webp")).isNull()
    }

    @Test
    fun `base URL만 있고 버킷이 없으면 업로드가 no-op이다`() {
        val storage = storage(bucket = "", baseUrl = "https://cdn.example.test")

        assertThat(storage.upload("characters/generated/story/image.webp", BYTES, "image/webp")).isNull()
    }

    @Test
    fun `미구성 상태의 삭제는 아무 일도 하지 않는다`() {
        val storage = storage(bucket = "", baseUrl = "")

        assertThatCode { storage.delete("characters/generated/story/image.webp") }.doesNotThrowAnyException()
    }

    private fun storage(bucket: String, baseUrl: String) =
        S3GeneratedImageStorage(bucket = bucket, region = "", endpoint = "", baseUrl = baseUrl)

    private companion object {
        val BYTES = byteArrayOf(1, 2, 3)
    }
}
