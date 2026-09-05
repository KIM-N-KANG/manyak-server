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
