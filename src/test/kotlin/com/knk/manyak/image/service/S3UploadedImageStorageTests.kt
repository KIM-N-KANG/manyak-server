package com.knk.manyak.image.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * HEAD 실패를 "없음"과 "그 밖의 오류"로 가르는 규칙을 고정한다(KNK-1126 Codex 지적).
 *
 * S3의 HEAD 응답에는 본문이 없어 SDK가 오류 코드를 읽지 못한다. 그래서 없는 객체가 [software.amazon.awssdk
 * .services.s3.model.NoSuchKeyException]이 아니라 404를 담은 [S3Exception]으로 온다. 상태 코드로 가르지 않으면
 * 아직 PUT하지 않은 정상 흐름이 500이 되어 클라이언트의 재시도 경로(400 UPLOAD_NOT_FOUND)가 막힌다.
 */
class S3UploadedImageStorageTests {

    @Test
    fun `실시간 슬롯은 WebP와 10분을 서명하고 길이는 고정하지 않는다`() {
        val signer = software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
            .region(software.amazon.awssdk.regions.Region.US_EAST_1)
            .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test-access", "test-secret"),
            )).build()
        signer.use {
            val storage = object : S3UploadedImageStorage("assets", "us-east-1", "", "https://cdn.test/") {
                override val presigner = signer
            }
            val url = storage.presignRealtimeImage("chat-images/chat/1-test.webp", java.time.Duration.ofMinutes(10))!!
            val decoded = java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8)
            assertThat(decoded).contains("X-Amz-Expires=600").contains("content-type").doesNotContain("content-length")
            assertThat(storage.serveUrlOf("chat-images/chat/1-test.webp"))
                .isEqualTo("https://cdn.test/chat-images/chat/1-test.webp")
        }
    }

    @Test
    fun `없는 객체의 404는 없음으로 본다`() {
        val storage = storage(s3Error(404))

        assertThat(storage.head(OBJECT_KEY)).isNull()
    }

    @Test
    fun `권한 오류 403은 없음으로 뭉개지 않고 그대로 던진다`() {
        val storage = storage(s3Error(403))

        assertThatThrownBy { storage.head(OBJECT_KEY) }.isInstanceOf(S3Exception::class.java)
    }

    private fun storage(error: S3Exception) = StubbedStorage(ThrowingS3Client(error))

    private fun s3Error(statusCode: Int): S3Exception =
        S3Exception.builder().statusCode(statusCode).message("head failed").build() as S3Exception

    /** 설정은 활성 상태로 두고 S3 클라이언트만 가짜로 갈아끼운다. */
    private class StubbedStorage(override val client: S3Client?) : S3UploadedImageStorage(
        bucket = "manyak-assets",
        region = "",
        endpoint = "",
        baseUrl = "https://cdn.example.test",
    )

    private class ThrowingS3Client(private val error: RuntimeException) : S3Client {
        override fun serviceName(): String = "s3"

        override fun close() = Unit

        override fun headObject(headObjectRequest: HeadObjectRequest): HeadObjectResponse = throw error
    }

    private companion object {
        const val OBJECT_KEY = "thumbnails/uploaded/8c1f/cover-1.webp"
    }
}
