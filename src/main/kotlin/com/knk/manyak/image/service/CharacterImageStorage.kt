package com.knk.manyak.image.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

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
    @param:Value("\${manyak.asset.image-base-url:}") private val baseUrl: String,
) : CharacterImageStorage {

    // 버킷·base URL 중 하나라도 없으면 URL을 만들 수 없으니 아예 클라이언트를 띄우지 않는다.
    private val client: S3Client? by lazy {
        if (bucket.isBlank() || baseUrl.isBlank()) {
            null
        } else {
            S3Client.builder()
                .apply { if (region.isNotBlank()) region(Region.of(region)) }
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
}
