package com.knk.manyak.image.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration

/** 업로드된 객체의 실제 메타데이터. 연결 시 서버가 이 값으로 크기·형식을 재검증한다. */
data class UploadedObject(val contentType: String?, val contentLength: Long)

/**
 * 사용자가 직접 올리는 이미지의 presign·검증 포트(KNK-1126, 스펙 §4-3-8).
 *
 * 파일이 서버 메모리·대역폭을 지날 이유가 없어 클라이언트가 S3에 **직접** PUT한다. 서버가 할 일은 두 가지다:
 * 서명된 URL을 발급하고([presignPut]), 연결 시점에 그 객체가 실제로 올라왔는지 확인하는 것([head]).
 *
 * 저장소가 미구성이면(로컬 기본) 두 메서드 모두 null이다. 호출부는 그때 기능을 비활성으로 보고 503을 낸다 —
 * 업로드는 저장소 없이 흉내 낼 수 없어, 생성 이미지([GeneratedImageStorage])처럼 조용히 건너뛸 수 없다.
 */
interface UploadedImageStorage {
    /** 서명된 PUT URL. `Content-Type`·`Content-Length`를 서명에 고정하므로 클라이언트는 요청한 값 그대로 보내야 한다. */
    fun presignPut(objectKey: String, contentType: String, contentLength: Long, expiresIn: Duration): String?

    /** 객체 메타데이터. 객체가 없으면 null이다. 저장소 미구성이면 null이라 호출부가 [isEnabled]로 먼저 가른다. */
    fun head(objectKey: String): UploadedObject?

    /** 저장소가 구성돼 업로드 기능을 쓸 수 있는지. */
    fun isEnabled(): Boolean

    /** 서빙 절대 URL. 미구성이면 null. */
    fun serveUrlOf(objectKey: String): String?
}

/**
 * S3 구현. 설정은 생성 이미지와 **같은 버킷**(`manyak.asset.character-image.*`)을 재사용한다 — 같은 assets
 * 버킷·같은 CloudFront라 키를 새로 나눌 이유가 없고, 설정 키를 늘리면 terraform 태스크 정의와 동반 배포가
 * 또 필요해진다([S3GeneratedImageStorage]의 같은 이유).
 *
 * 버킷과 base URL이 **둘 다** 있어야 활성화한다. 하나라도 비면 반쪽 설정으로 잘못된 URL이 DB에 굳는다.
 */
@Component
class S3UploadedImageStorage(
    @param:Value("\${manyak.asset.character-image.bucket:}") private val bucket: String,
    @param:Value("\${manyak.asset.character-image.region:}") private val region: String,
    @param:Value("\${manyak.asset.character-image.endpoint:}") private val endpoint: String,
    @param:Value("\${manyak.asset.character-image.base-url:}") private val baseUrl: String,
) : UploadedImageStorage {

    private val configured: Boolean get() = bucket.isNotBlank() && baseUrl.isNotBlank()

    private val presigner: S3Presigner? by lazy {
        if (!configured) {
            null
        } else {
            S3Presigner.builder()
                .apply { if (region.isNotBlank()) region(Region.of(region)) }
                // MinIO 같은 S3 호환 저장소는 가상 호스트 주소를 해석하지 못하므로 path-style로 서명한다.
                .apply {
                    if (endpoint.isNotBlank()) {
                        endpointOverride(URI.create(endpoint))
                        serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    }
                }
                .build()
        }
    }

    private val client: S3Client? by lazy {
        if (!configured) {
            null
        } else {
            S3Client.builder()
                .apply { if (region.isNotBlank()) region(Region.of(region)) }
                .apply {
                    if (endpoint.isNotBlank()) {
                        endpointOverride(URI.create(endpoint))
                        forcePathStyle(true)
                    }
                }
                // HEAD 한 번에 요청 스레드가 오래 묶이지 않게 상한을 둔다(연결 API는 사용자 대기 경로다).
                .overrideConfiguration(
                    ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(Duration.ofSeconds(2))
                        .build(),
                )
                .build()
        }
    }

    override fun isEnabled(): Boolean = configured

    override fun presignPut(objectKey: String, contentType: String, contentLength: Long, expiresIn: Duration): String? {
        val signer = presigner ?: return null
        val put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            // 서명에 고정한다 — 클라이언트가 다른 형식·크기로 바꿔 올릴 수 없다.
            .contentType(contentType)
            .contentLength(contentLength)
            .build()
        return signer.presignPutObject(
            PutObjectPresignRequest.builder().signatureDuration(expiresIn).putObjectRequest(put).build(),
        ).url().toString()
    }

    override fun head(objectKey: String): UploadedObject? {
        val s3 = client ?: return null
        return try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build())
                .let { UploadedObject(contentType = it.contentType(), contentLength = it.contentLength() ?: 0L) }
        } catch (ignored: NoSuchKeyException) {
            // 아직 PUT하지 않았거나 다른 키다. 호출부가 400 UPLOAD_NOT_FOUND로 바꾼다.
            null
        }
    }

    override fun serveUrlOf(objectKey: String): String? =
        if (configured) "${baseUrl.trimEnd('/')}/$objectKey" else null
}
