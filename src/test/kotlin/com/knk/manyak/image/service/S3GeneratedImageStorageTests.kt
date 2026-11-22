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

    // 업로드와 보상 삭제는 서버 자신의 호출이라 내부 주소가 있으면 그쪽으로 간다(KNK-2125, S3UploadedImageStorageTests 참고).
    @Test
    fun `내부 엔드포인트가 있으면 업로드 클라이언트가 그 주소로 간다`() {
        val storage = EndpointProbe(endpoint = "https://dev-s3.example.test", internalEndpoint = "http://minio:9000")

        assertThat(storage.clientEndpoint()).isEqualTo(java.net.URI.create("http://minio:9000"))
    }

    @Test
    fun `내부 엔드포인트가 없으면 업로드 클라이언트가 공개 엔드포인트로 간다`() {
        val storage = EndpointProbe(endpoint = "https://dev-s3.example.test", internalEndpoint = "")

        assertThat(storage.clientEndpoint()).isEqualTo(java.net.URI.create("https://dev-s3.example.test"))
    }

    private class EndpointProbe(endpoint: String, internalEndpoint: String) : S3GeneratedImageStorage(
        bucket = "manyak-character-images",
        region = "us-east-1",
        endpoint = endpoint,
        baseUrl = "https://cdn.example.test",
        internalEndpoint = internalEndpoint,
    ) {
        fun clientEndpoint() = client!!.use { it.serviceClientConfiguration().endpointOverride().orElse(null) }
    }

    private fun storage(bucket: String, baseUrl: String) =
        S3GeneratedImageStorage(bucket = bucket, region = "", endpoint = "", baseUrl = baseUrl)

    private companion object {
        val BYTES = byteArrayOf(1, 2, 3)
    }
}
