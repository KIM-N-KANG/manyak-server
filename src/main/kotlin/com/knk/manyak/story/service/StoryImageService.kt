package com.knk.manyak.story.service

import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.image.service.UploadedImageKind
import com.knk.manyak.image.service.UploadedImageObjectKeys
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.story.dto.AddCharacterImageRequest
import com.knk.manyak.story.dto.CharacterImageResponse
import com.knk.manyak.story.dto.ImagePresignRequest
import com.knk.manyak.story.dto.ImagePresignResponse
import com.knk.manyak.story.entity.StoryCharacterImage
import com.knk.manyak.story.repository.StoryCharacterImageRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

/**
 * 스토리 이미지 업로드(KNK-1126, 스펙 §4-3-8 스토리 이미지 업로드).
 *
 * 클라이언트가 S3에 **직접** 올리고(presigned PUT), 서버는 발급과 연결 검증만 한다. 파일이 서버 메모리·대역폭을
 * 지날 이유가 없고 변환도 하지 않기 때문이다. 소유권·객체 검증은 [StoryImageAccess]가 소유한다.
 *
 * **회원 소유 스토리만**이다. 게스트 소유(`user_id` NULL)는 이관 뒤에 올린다(400).
 *
 * 인물 이미지 등록은 이 클래스가 트랜잭션을 열지 않는다 — 유니크 위반을 409로 바꾸는 일은 트랜잭션 **밖**에서
 * 해야 커밋 시점에 드러난 위반도 같은 409로 모인다([com.knk.manyak.user.service.UserProfileService] 선례).
 */
@Service
class StoryImageService(
    private val storyImageAccess: StoryImageAccess,
    private val storyCharacterImageRepository: StoryCharacterImageRepository,
    private val uploadedImageStorage: UploadedImageStorage,
    private val suspensionGuard: SuspensionGuard,
    private val characterImageAdder: CharacterImageAdder,
) {

    @Transactional(readOnly = true)
    fun presign(storyId: String, userId: Long, request: ImagePresignRequest): ImagePresignResponse {
        suspensionGuard.requireActive(userId) // 정지 계정 쓰기 차단(스펙 §4-5 B20).
        val story = storyImageAccess.resolveOwnedStory(storyId, userId)
        val kind = requireNotNull(request.kind)
        val contentType = storyImageAccess.requireSupportedContentType(request.contentType)
        storyImageAccess.requireUploadEnabled()

        val objectKey = UploadedImageObjectKeys.newObjectKey(kind, story.publicId, contentType)
        val uploadUrl = uploadedImageStorage.presignPut(
            objectKey = objectKey,
            contentType = contentType,
            contentLength = requireNotNull(request.contentLength),
            expiresIn = PRESIGN_EXPIRY,
        ) ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이미지 업로드가 설정되지 않았습니다.")
        return ImagePresignResponse(
            uploadUrl = uploadUrl,
            objectKey = objectKey,
            expiresInSeconds = PRESIGN_EXPIRY.seconds,
        )
    }

    /** 표지 삭제(스펙 §4-3-8). 업로드·생성 URL만 지워 프리셋 폴백으로 내린다. 없어도 204(멱등). */
    @Transactional
    fun deleteThumbnail(storyId: String, userId: Long) {
        suspensionGuard.requireActive(userId)
        val story = storyImageAccess.resolveOwnedStory(storyId, userId)
        // S3 객체는 지우지 않는다 — 지난 채팅 카드·스냅샷이 그 URL을 가리킬 수 있다(스펙 결정 기록).
        story.thumbnailImageUrl = null
        // 상태도 되돌린다. 남겨 두면 다음에 올린 표지가 옛 판정(PENDING·REJECTED)을 물려받아 안 보인다.
        story.thumbnailModerationStatus = ImageModerationStatus.APPROVED
    }

    fun addCharacterImage(
        storyId: String,
        characterId: String,
        userId: Long,
        request: AddCharacterImageRequest,
    ): CharacterImageResponse =
        try {
            characterImageAdder.add(storyId, characterId, userId, request)
        } catch (exception: DataIntegrityViolationException) {
            // 사전 조회와 커밋 사이에 같은 이름이 들어왔다. (character_id, image_name) 유니크가 최종 방어선이다.
            throw StoryImageAccess.duplicateImageName(exception)
        }

    /** 인물 이미지 삭제. **S3 객체는 남긴다** — 지난 채팅의 `[[URL]]` 마커가 깨진다(스펙 결정 기록). 없어도 204. */
    @Transactional
    fun deleteCharacterImage(storyId: String, characterId: String, imageId: String, userId: Long) {
        suspensionGuard.requireActive(userId)
        val story = storyImageAccess.resolveOwnedStory(storyId, userId)
        val character = storyImageAccess.resolveCharacter(story, characterId)
        val imagePublicId = StoryImageAccess.parsePublicIdOrNull(imageId) ?: return
        storyCharacterImageRepository.findByCharacterIdAndPublicId(character.id, imagePublicId)
            ?.let(storyCharacterImageRepository::delete)
    }

    companion object {
        /** presign 만료. 짧게 두어 유출된 URL의 수명을 제한한다(스펙 §4-3-8 — 10분). */
        val PRESIGN_EXPIRY: Duration = Duration.ofMinutes(10)
    }
}

/**
 * 인물 이미지 등록의 트랜잭션 단위. 별도 빈인 이유는 유니크 위반 변환을 트랜잭션 **밖**에 두기 위해서다
 * ([StoryImageService] 참조 — 같은 클래스 자기 호출은 프록시를 타지 않는다).
 */
@Component
class CharacterImageAdder(
    private val storyImageAccess: StoryImageAccess,
    private val storyCharacterRepository: StoryCharacterRepository,
    private val storyCharacterImageRepository: StoryCharacterImageRepository,
    private val suspensionGuard: SuspensionGuard,
) {

    @Transactional
    fun add(
        storyId: String,
        characterId: String,
        userId: Long,
        request: AddCharacterImageRequest,
    ): CharacterImageResponse {
        suspensionGuard.requireActive(userId)
        val story = storyImageAccess.resolveOwnedStory(storyId, userId)
        val character = storyImageAccess.resolveCharacter(story, characterId)
        val imageName = requireValidImageName(character.name, requireNotNull(request.imageName))

        // 상한 판정 전에 인물 행을 잠근다. 잠그지 않으면 상한 직전의 동시 추가 둘이 모두 개수를 읽고 통과해
        // 11장이 되고 sort_order도 겹친다(검수 지적). 같은 트랜잭션 안에서 count → insert가 직렬화된다.
        storyCharacterRepository.findByIdForUpdate(character.id)
        val existing = storyCharacterImageRepository.findByCharacterIdOrderBySortOrderAscIdAsc(character.id)
        if (existing.size >= StoryCharacterImage.MAX_IMAGES_PER_CHARACTER) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "인물당 이미지는 ${StoryCharacterImage.MAX_IMAGES_PER_CHARACTER}장까지 올릴 수 있습니다.",
            )
        }
        if (existing.any { it.imageName == imageName }) {
            throw StoryImageAccess.duplicateImageName()
        }
        val imageUrl = storyImageAccess.resolveUploadedUrl(
            story,
            UploadedImageKind.CHARACTER,
            requireNotNull(request.objectKey),
        )
        val saved = storyCharacterImageRepository.save(
            StoryCharacterImage(
                character = character,
                imageName = imageName,
                imageUrl = imageUrl,
                // 표시 순서는 등록 순서다. 재정렬 API는 아직 없다.
                sortOrder = existing.size,
            ),
        )
        // 위반을 커밋까지 미루지 않고 여기서 드러낸다. 잡지는 않는다 — 409 변환은 트랜잭션 밖의 몫이다.
        storyCharacterImageRepository.flush()
        return CharacterImageResponse(
            id = saved.publicId.toString(),
            imageName = saved.imageName,
            imageUrl = saved.imageUrl,
            moderationStatus = saved.moderationStatus,
        )
    }
}

/**
 * 인물 이미지 이름 규칙(스펙 §4-3-8): `{인물이름}_{접미}`. 접미는 1~20자 한글·영문·숫자다.
 *
 * 인물 이름을 접두로 강제하는 이유는 AI가 대사 문맥으로 이미지를 고를 때(KNK-1199) 이름이 곧 인물 식별이기
 * 때문이다. 접미는 표정·상황·감정을 담는다(`세린_기본`, `세린_웃음`).
 */
internal fun requireValidImageName(characterName: String, imageName: String): String {
    val trimmed = imageName.trim()
    val expectedPrefix = "${characterName}_"
    if (!trimmed.startsWith(expectedPrefix) || !IMAGE_NAME_SUFFIX.matches(trimmed.removePrefix(expectedPrefix))) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "이미지 이름은 '${characterName}_접미' 형식이어야 합니다(접미는 1~20자 한글·영문·숫자).",
        )
    }
    return trimmed
}

private val IMAGE_NAME_SUFFIX = Regex("^[가-힣a-zA-Z0-9]{1,20}$")
