package com.knk.manyak.story.admin

import com.knk.manyak.image.service.UploadedImageKind
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.story.dto.GeneralCharacterInput
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.service.StoryImageAccess
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** 검수 제출본 없이도 PUT 원본과 승인 저장 URL을 분리해, 같은 presign 재사용으로 라이브가 바뀌지 않게 한다. */
@Component
class OriginalImages(
    private val access: StoryImageAccess,
    private val storage: UploadedImageStorage,
    private val edit: com.knk.manyak.story.service.StoryEditService,
) {
    fun prepare(owner: Long, story: Story?, thumbnail: String?, characters: List<GeneralCharacterInput>?): Map<String, String> {
        val sources = linkedMapOf<String, UploadedImageKind>()
        thumbnail?.let { sources[it] = UploadedImageKind.COVER }
        val existing = if (story != null && !characters.isNullOrEmpty()) edit.getEditForm(story.publicId.toString(), owner).characters else emptyList()
        characters.orEmpty().forEach { character ->
            val previous = existing.firstOrNull { it.id == character.id }
            val names = character.images.orEmpty().mapNotNull { image ->
                image.imageName?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.images?.firstOrNull { it.id == image.id }?.imageName?.let { old ->
                    if (old.startsWith("${previous.name}_")) "${character.name}_${old.removePrefix("${previous.name}_")}" else old
                }
            }
            if (names.size != names.toSet().size) throw StoryImageAccess.duplicateImageName()
            character.images.orEmpty().forEach { image ->
                if (!image.hasExactlyOneSource() || !image.hasImageNameWhenNew()) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지 항목의 입력이 올바르지 않습니다.")
                }
                image.objectKey?.let { sources[it] = UploadedImageKind.CHARACTER }
            }
        }
        if (sources.isEmpty()) return emptyMap()
        val publicId = access.resolveUserPublicId(owner)
        // 모두 검증한 뒤 복사한다. 접두어 검증은 HEAD보다 먼저다.
        try {
            sources.forEach { (key, kind) ->
                if (story == null) access.resolveDraftUploadedUrl(publicId, kind, key)
                else access.resolveUploadedUrl(story, publicId, kind, key)
            }
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이미지 저장소를 사용할 수 없습니다.", e)
        }
        return sources.mapValues { (source, _) ->
            val destination = "${source.substringBefore("/uploaded/")}/uploaded/moderated/${UUID.randomUUID()}.${source.substringAfterLast('.')}"
            try {
                storage.copy(source, destination)
                storage.serveUrlOf(destination) ?: throw IllegalStateException("Image storage is unavailable")
            } catch (e: Exception) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이미지 저장소를 사용할 수 없습니다.", e)
            }
        }
    }
}
