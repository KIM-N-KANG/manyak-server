package com.knk.manyak.image.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.time.Duration

/**
 * 컴파일이 생성한 인물 이미지를 객체 저장소에 올리고 서빙 URL을 돌려주는 포트(KNK-966).
 *
 * 저장소가 미구성이면 null을 돌려 이미지 없이 진행하게 한다(로컬·테스트). 업로드 실패는 예외로 던지고
 * 호출부가 인물 단위로 흡수한다 — 이미지 한 장 때문에 스토리 생성이 실패해서는 안 된다.
 */
interface CharacterImageStorage {
    fun upload(objectKey: String, bytes: ByteArray, contentType: String): String?

    /**
     * 업로드한 객체를 지운다. 저장을 못 마친 이미지가 저장소에 고아로 남지 않게 호출부가 보상 삭제에 쓴다.
     * 실패는 예외로 던지고 호출부가 흡수한다(삭제 실패가 원래 예외를 가려서는 안 된다).
     */
    fun delete(objectKey: String)
}

/**
 * S3 구현. 버킷이 비어 있으면(로컬·테스트) 클라이언트를 만들지 않고 no-op으로 동작한다.
 *
 * 서빙 URL은 저장하지 않는 프리셋([ImageUrlResolver])과 달리 생성 자산이라 키가 UUID다. 그래서 조합 규칙을
 * 카탈로그에 두지 않고 업로드 시점에 `image-base-url + 객체 키`로 만들어 `story_characters.image_url`에 굳힌다.
 */
@Component
class S3CharacterImageStorage(
    @param:Value("\${manyak.asset.character-image.bucket:}") private val bucket: String,
    @param:Value("\${manyak.asset.character-image.region:}") private val region: String,
    // S3 호환 저장소(로컬 MinIO 등)를 쓸 때만 채운다. 비어 있으면 AWS 기본 엔드포인트를 그대로 쓴다.
    @param:Value("\${manyak.asset.character-image.endpoint:}") private val endpoint: String,
    // 인물 이미지 전용 서빙 base URL. 전역 image-base-url(기본값이 운영 CDN)로 폴백하지 않는다 —
    // dev에서 버킷만 바꾸면 dev DB에 운영 CDN 절대 URL이 저장돼 깨진 이미지가 남기 때문이다.
    @param:Value("\${manyak.asset.character-image.base-url:}") private val baseUrl: String,
) : CharacterImageStorage {

    // 버킷과 전용 base URL이 **둘 다** 있어야 활성화한다. 하나라도 비면 클라이언트를 만들지 않고 no-op으로
    // 떨어진다 — 반쪽 설정으로 잘못된 URL이 DB에 굳는 것을 원천 차단한다(이미지 없이 저장되는 편이 안전하다).
    private val client: S3Client? by lazy {
        if (bucket.isBlank() || baseUrl.isBlank()) {
            null
        } else {
            S3Client.builder()
                .apply { if (region.isNotBlank()) region(Region.of(region)) }
                // MinIO 같은 S3 호환 저장소는 가상 호스트 방식(bucket.host) 주소를 해석하지 못하므로 path-style을 켠다.
                // 자격증명은 손대지 않는다 — AWS 기본 체인(AWS_ACCESS_KEY_ID·AWS_SECRET_ACCESS_KEY 등)을 그대로 쓴다.
                .apply {
                    if (endpoint.isNotBlank()) {
                        endpointOverride(URI.create(endpoint))
                        forcePathStyle(true)
                    }
                }
                // SDK 기본값에는 API 호출 타임아웃이 없어, S3가 느리거나 half-open이면 동기 putObject가 재시도까지
                // 물고 늘어진다. 인물은 최대 5명이라 호출 전체 상한 10초면 최악 누적이 50초로 묶이고, 이는 compile
                // (최대 180초) 뒤에 붙는 부가 작업이라 스토리 생성 요청 예산을 위협하지 않는다. 시도당 5초는 그 안에서
                // 재시도 한 번을 허용하는 값이다. 업로드와 보상 삭제 모두 이 클라이언트를 쓰므로 같은 상한이 걸린다.
                .overrideConfiguration(
                    ClientOverrideConfiguration.builder()
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .build(),
                )
                .build()
        }
    }

    override fun upload(objectKey: String, bytes: ByteArray, contentType: String): String? {
        val s3 = client ?: return null
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(bytes),
        )
        return "${baseUrl.trimEnd('/')}/$objectKey"
    }

    override fun delete(objectKey: String) {
        val s3 = client ?: return
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build())
    }

    private companion object {
        // 재시도를 포함한 호출 하나의 전체 상한. 단계 예산([ImageStageBudget])이 새 호출을 시작할지 판단할 때
        // 같은 값을 여유로 쓰므로 한 곳에 둔다 — 두 값이 어긋나면 마감을 넘겨 끝나는 호출이 생긴다.
        val API_CALL_TIMEOUT: Duration = ImageStageBudget.CALL_TIMEOUT

        // 시도 하나의 상한. 전체 상한 안에서 재시도 한 번이 들어갈 수 있는 값이다.
        val API_CALL_ATTEMPT_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
