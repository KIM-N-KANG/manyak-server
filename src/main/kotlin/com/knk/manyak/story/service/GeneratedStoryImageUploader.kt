package com.knk.manyak.story.service

import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.image.service.GeneratedImageObjectKeys
import com.knk.manyak.image.service.GeneratedImageStorage
import com.knk.manyak.image.service.ImageStageBudget
import com.knk.manyak.story.client.AiCharacterImage
import com.knk.manyak.story.client.AiThumbnailImage
import com.knk.manyak.story.repository.StoryRepository
import org.springframework.stereotype.Component
import java.util.Base64
import java.util.UUID

/**
 * 간편 제작 완성의 생성 이미지(인물, 표지)를 저장소에 올리고, 저장이 실패하면 스토리가 없을 때만 지운다
 * (KNK-2056에서 옛 SimpleStoryCreationService에서 추출, 호출자는 [SimpleStoryCompletionService]). 업로드는 DB 트랜잭션 밖에서 부르고, 업로드와 보상 삭제는
 * 호출자가 만든 같은 예산([ImageStageBudget])을 나눠 쓴다.
 */
@Component
class GeneratedStoryImageUploader(
    private val generatedImageStorage: GeneratedImageStorage,
    private val storyRepository: StoryRepository,
    private val structuredLogger: StructuredLogger,
) {

    /** 업로드한 객체 키와 그 서빙 URL. 저장 실패 시 보상 삭제에 객체 키가 필요해 URL만 들고 다니지 않는다. */
    data class UploadedImage(val objectKey: String, val url: String)

    /**
     * 컴파일이 생성한 인물 이미지를 S3에 올리고 `인물 이름 → 업로드 결과` 맵을 만든다(스펙 §4-4, KNK-966).
     *
     * **DB 트랜잭션 밖에서** 호출한다(네트워크 I/O가 커넥션을 붙잡지 않게). 디코딩·업로드 실패는 인물 단위로 흡수해
     * 그 인물만 이미지 없이 저장되게 하고, 스토리 생성은 계속한다(graceful — 엔딩 빈 배열과 같은 원칙).
     * [AiCharacterImage.error]가 있는 항목은 base64가 함께 와도 실패로 보고 건너뛴다(에러 코드가 우선).
     */
    fun uploadCharacterImages(
        storyPublicId: UUID,
        imagesByName: Map<String, AiCharacterImage>,
        budget: ImageStageBudget,
    ): Map<String, UploadedImage> = imagesByName.mapNotNull { (name, image) ->
        if (image.error != null) {
            return@mapNotNull null
        }
        if (!budget.hasRoomForCall()) {
            // 예산이 끝났으면 남은 인물은 올리지 않는다 — 저장소 지연이 스토리 생성 요청을 끌고 가지 않게 한다.
            logBudgetExhausted(storyPublicId, "character_image", "upload")
            return@mapNotNull null
        }
        val base64 = image.imageBase64?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // 디코딩은 업로드 밖에서 먼저 끝낸다 — 깨진 base64는 올라간 객체가 없으니 아래 보상 삭제도 필요 없다.
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (exception: Exception) {
            logGeneratedImageEvent("character_image_upload_failed", storyPublicId, exception)
            return@mapNotNull null
        }
        // 파일명에 인물 이름을 드러낸다(KNK-1010). 이름은 이미 정규화(trim·100자 절단)된 저장 이름이며,
        // 키에 쓸 수 없는 문자는 [GeneratedImageObjectKeys]가 걸러낸다.
        val objectKey = GeneratedImageObjectKeys.newObjectKey(
            GeneratedImageObjectKeys.KEY_PREFIX_CHARACTER,
            storyPublicId,
            name,
        )
        val url = try {
            generatedImageStorage.upload(
                objectKey,
                bytes,
                image.contentType?.takeIf { it.isNotBlank() } ?: GENERATED_IMAGE_DEFAULT_CONTENT_TYPE,
            )
        } catch (exception: Exception) {
            // 이미지 한 장 때문에 스토리 생성이 실패해서는 안 된다. 사유만 남기고 이미지 없이 진행한다.
            logGeneratedImageEvent("character_image_upload_failed", storyPublicId, exception)
            // 예외가 났어도 저장소는 객체를 받았을 수 있다(응답만 유실된 경우). 이 키는 반환값에 실리지 않아
            // 트랜잭션 보상 삭제가 닿지 못하므로 여기서 지운다. 실제로 안 올라갔으면 삭제는 무해한 no-op이다.
            deleteGeneratedImageQuietly(storyPublicId, objectKey, budget)
            null
        }
        url?.let { name to UploadedImage(objectKey, it) }
    }.toMap()

    /**
     * 컴파일이 생성한 표지 썸네일을 S3에 올린다(KNK-1069). 실패하면 표지 URL과 노출 표지는 null이며 스토리 생성은 계속한다.
     *
     * 인물 이미지와 같은 원칙이다 — **DB 트랜잭션 밖에서** 호출하고, 디코딩·업로드 실패는 흡수해 표지 없이
     * 저장을 이어간다(표지 한 장 때문에 스토리 생성이 실패해서는 안 된다). [AiThumbnailImage.error]가 있으면
     * base64가 함께 와도 실패로 본다(에러 코드가 우선). 구버전 AI는 필드 자체를 보내지 않아 [image]가 null이다.
     */
    fun uploadThumbnailImage(
        storyPublicId: UUID,
        image: AiThumbnailImage?,
        budget: ImageStageBudget,
    ): UploadedImage? {
        if (image == null || image.error != null) {
            return null
        }
        if (!budget.hasRoomForCall()) {
            logBudgetExhausted(storyPublicId, "thumbnail_image", "upload")
            return null
        }
        val base64 = image.imageBase64?.takeIf { it.isNotBlank() } ?: return null
        // 디코딩은 업로드 밖에서 먼저 끝낸다 — 깨진 base64는 올라간 객체가 없으니 보상 삭제도 필요 없다.
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (exception: Exception) {
            logGeneratedImageEvent("thumbnail_image_upload_failed", storyPublicId, exception)
            return null
        }
        // 파일명은 AI가 준 image_name을 sanitize해 쓴다(비었으면 `thumbnail`). 이름은 LLM 산출물이라 신뢰 경계다.
        val objectKey = GeneratedImageObjectKeys.newObjectKey(
            GeneratedImageObjectKeys.KEY_PREFIX_THUMBNAIL,
            storyPublicId,
            image.imageName.orEmpty(),
            GeneratedImageObjectKeys.FALLBACK_NAME_THUMBNAIL,
        )
        val url = try {
            generatedImageStorage.upload(
                objectKey,
                bytes,
                image.contentType?.takeIf { it.isNotBlank() } ?: GENERATED_IMAGE_DEFAULT_CONTENT_TYPE,
            )
        } catch (exception: Exception) {
            logGeneratedImageEvent("thumbnail_image_upload_failed", storyPublicId, exception)
            // 예외가 났어도 저장소는 객체를 받았을 수 있다(응답만 유실된 경우). 이 키는 반환값에 실리지 않아
            // 트랜잭션 보상 삭제가 닿지 못하므로 여기서 지운다. 실제로 안 올라갔으면 삭제는 무해한 no-op이다.
            deleteGeneratedImageQuietly(storyPublicId, objectKey, budget)
            null
        }
        return url?.let { UploadedImage(objectKey, it) }
    }

    /**
     * [block]이 예외로 끝나면 이번 요청에서 올린 생성 이미지(인물·표지)를 best-effort로 지우고 원래 예외를 다시 던진다.
     *
     * 업로드는 트랜잭션보다 먼저 끝나므로, 저장이 409·502·DB 오류로 깨지면 객체 키가 어디에도 기록되지 않은
     * 고아 객체가 남는다(키가 매번 UUID라 재시도로도 회수되지 않는다). 삭제 실패는 로그만 남긴다 —
     * 정리 실패가 원래 실패 원인을 가려서는 안 된다.
     */
    fun <T> runWithGeneratedImageCleanup(
        storyPublicId: UUID,
        uploadedObjectKeys: List<String>,
        budget: ImageStageBudget,
        block: () -> T,
    ): T {
        try {
            return block()
        } catch (throwable: Throwable) {
            if (uploadedObjectKeys.isNotEmpty() && isStoryAbsent(storyPublicId)) {
                uploadedObjectKeys.forEach { objectKey ->
                    deleteGeneratedImageQuietly(storyPublicId, objectKey, budget)
                }
            }
            throw throwable
        }
    }

    /**
     * 보상 삭제를 해도 되는지 판정한다 — 스토리 행이 **없을 때만** true다.
     *
     * 커밋이 서버에 반영됐는데 응답만 유실되면 예외가 나도 스토리와 `image_url`이 영구히 남는다. 그때 객체를 지우면
     * DB가 가리키는 이미지가 깨져 사용자에게 보인다. 그래서 삭제 전에 트랜잭션 밖에서(이 시점엔 실패한 트랜잭션이
     * 이미 끝나 있다) 스토리 존재를 확인하고, 남아 있으면 지우지 않는다.
     *
     * 확인 자체가 실패해도 지우지 않는다. **고아 객체(저장 비용)가 깨진 이미지(사용자 피해)보다 싸다**는 판단이다.
     */
    private fun isStoryAbsent(storyPublicId: UUID): Boolean = try {
        val exists = storyRepository.existsByPublicId(storyPublicId)
        if (exists) {
            structuredLogger.event(
                "generated_image_cleanup_skipped",
                "story_public_id" to storyPublicId.toString(),
                "reason" to "story_exists",
            )
        }
        !exists
    } catch (exception: Exception) {
        structuredLogger.event(
            "generated_image_cleanup_skipped",
            "story_public_id" to storyPublicId.toString(),
            "reason" to "lookup_failed",
            "error_type" to (exception::class.simpleName ?: "UNKNOWN"),
        )
        false
    }

    /**
     * 고아가 될 객체를 지운다. 삭제 실패는 로그만 남긴다 — 정리 실패가 원래 실패 원인을 가려서는 안 된다.
     * 예산이 끝났으면 삭제도 건너뛴다(정리 때문에 요청이 더 늘어지면 안 된다 — 남은 객체는 고아로 수용).
     */
    private fun deleteGeneratedImageQuietly(storyPublicId: UUID, objectKey: String, budget: ImageStageBudget) {
        val kind = imageKindOf(objectKey)
        if (!budget.hasRoomForCall()) {
            logBudgetExhausted(storyPublicId, kind, "cleanup")
            return
        }
        try {
            generatedImageStorage.delete(objectKey)
        } catch (exception: Exception) {
            logGeneratedImageEvent("${kind}_cleanup_failed", storyPublicId, exception)
        }
    }

    /**
     * 로그 이벤트 접두어를 객체 키에서 되읽는다. 인물·표지가 같은 정리 경로를 지나므로, 어느 쪽이 실패했는지는
     * 키 prefix가 유일하게 남은 단서다(`character_image_*` · `thumbnail_image_*` 관례 유지).
     */
    private fun imageKindOf(objectKey: String): String =
        if (objectKey.startsWith(GeneratedImageObjectKeys.KEY_PREFIX_THUMBNAIL)) "thumbnail_image" else "character_image"

    private fun logBudgetExhausted(storyPublicId: UUID, kind: String, stage: String) {
        structuredLogger.event(
            "${kind}_budget_exhausted",
            "story_public_id" to storyPublicId.toString(),
            "stage" to stage,
        )
    }

    /** 생성 이미지 실패 관측용 구조화 로그. base64·URL은 싣지 않는다(개인정보·용량). */
    private fun logGeneratedImageEvent(eventName: String, storyPublicId: UUID, exception: Exception) {
        structuredLogger.event(
            eventName,
            "story_public_id" to storyPublicId.toString(),
            "error_type" to (exception::class.simpleName ?: "UNKNOWN"),
        )
    }

    private companion object {
        // 객체 키 형식은 [GeneratedImageObjectKeys]가 소유한다(스펙 §4-4). AI는 WebP만 보낸다.
        const val GENERATED_IMAGE_DEFAULT_CONTENT_TYPE = "image/webp"
    }
}
