package com.knk.manyak.story.service

import com.knk.manyak.search.event.StoryIndexRequestedEvent
import org.springframework.context.ApplicationEventPublisher
import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.image.service.UploadedImageKind
import com.knk.manyak.image.service.UploadedImageObjectKeys
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.story.dto.ImagePresignRequest
import com.knk.manyak.story.dto.ImagePresignResponse
import com.knk.manyak.story.repository.StoryCharacterImageRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

/** 이미지 presign과 참조 삭제. 추가·교체는 검수 제출본 승인 경로가 담당한다. */
@Service
class StoryImageService(
    private val eventPublisher: ApplicationEventPublisher,
    private val storyImageAccess: StoryImageAccess,
    private val storyCharacterImageRepository: StoryCharacterImageRepository,
    private val uploadedImageStorage: UploadedImageStorage,
    private val suspensionGuard: SuspensionGuard,
    private val storyPublicSnapshotService: StoryPublicSnapshotService,
    private val submissions: com.knk.manyak.story.submission.StorySubmissionRepository,
) {

    @Transactional(readOnly = true)
    fun presign(storyId: String, userId: Long, request: ImagePresignRequest): ImagePresignResponse {
        suspensionGuard.requireActive(userId) // 정지 계정 쓰기 차단(스펙 §4-5 B20).
        val story = storyImageAccess.resolveOwnedStory(storyId, userId)
        return issuePresign(request) { kind, contentType ->
            UploadedImageObjectKeys.newObjectKey(kind, story.publicId, contentType)
        }
    }

    /**
     * 스토리 없이 받는 presign(일반 제작 등록 전, KNK-1390). 소유 스코프가 스토리가 아니라 **사용자**라
     * 키가 `{prefix}/drafts/{userPublicId}/` 아래로 떨어진다. 등록 요청이 같은 규칙으로 키를 검증한다.
     */
    @Transactional(readOnly = true)
    fun presignDraft(userId: Long, request: ImagePresignRequest): ImagePresignResponse {
        suspensionGuard.requireActive(userId)
        val userPublicId = storyImageAccess.resolveUserPublicId(userId)
        return issuePresign(request) { kind, contentType ->
            UploadedImageObjectKeys.newDraftObjectKey(kind, userPublicId, contentType)
        }
    }

    /** 스토리 스코프와 draft 스코프가 공유하는 발급 절차. 키를 어디에 두느냐만 다르다. */
    private fun issuePresign(
        request: ImagePresignRequest,
        objectKeyOf: (UploadedImageKind, String) -> String,
    ): ImagePresignResponse {
        val contentType = storyImageAccess.requireSupportedContentType(request.contentType)
        storyImageAccess.requireUploadEnabled()

        val objectKey = objectKeyOf(requireNotNull(request.kind), contentType)
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
        // 스냅샷 갱신이 뒤따르므로 공개 범위 판정 전에 스토리를 잠근다(PR #273 Codex P1).
        val story = storyImageAccess.resolveOwnedStoryForUpdate(storyId, userId)
        if (submissions.existsByStoryIdAndStatus(story.id, com.knk.manyak.story.submission.SubmissionStatus.PENDING)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "검수 중에는 이미지를 삭제할 수 없습니다.")
        }
        // S3 객체는 지우지 않는다 — 지난 채팅 카드·스냅샷이 그 URL을 가리킬 수 있다(스펙 결정 기록).
        story.thumbnailImageUrl = null
        // 상태도 되돌린다. 남겨 두면 다음에 올린 표지가 옛 판정(PENDING·REJECTED)을 물려받아 안 보인다.
        story.thumbnailModerationStatus = ImageModerationStatus.APPROVED
        // 공개 스토리면 마지막 공개 재료도 같이 굳힌다(PR #273 Codex P2). 수정 API 밖에서 표지·인물 이미지를
        // 바꾸는 경로가 스냅샷을 갱신하지 않으면, 나중에 비공개로 내려갔을 때 기존 독자에게 가는 재료가
        // 공개 당시와 어긋난다(지운 이미지가 되살아나거나 추가한 이미지가 사라진다).
        storyPublicSnapshotService.refresh(story)
        eventPublisher.publishEvent(StoryIndexRequestedEvent(story.id))
    }

    /** 인물 이미지 삭제. **S3 객체는 남긴다** — 지난 채팅의 `[[URL]]` 마커가 깨진다(스펙 결정 기록). 없어도 204. */
    @Transactional
    fun deleteCharacterImage(storyId: String, characterId: String, imageId: String, userId: Long) {
        suspensionGuard.requireActive(userId)
        val story = storyImageAccess.resolveOwnedStoryForUpdate(storyId, userId)
        if (submissions.existsByStoryIdAndStatus(story.id, com.knk.manyak.story.submission.SubmissionStatus.PENDING)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "검수 중에는 이미지를 삭제할 수 없습니다.")
        }
        val character = storyImageAccess.resolveCharacter(story, characterId)
        val imagePublicId = StoryImageAccess.parsePublicIdOrNull(imageId) ?: return
        storyCharacterImageRepository.findByCharacterIdAndPublicId(character.id, imagePublicId)
            ?.let(storyCharacterImageRepository::delete)
        storyCharacterImageRepository.flush()
        storyPublicSnapshotService.refresh(story)
    }

    companion object {
        /** presign 만료. 짧게 두어 유출된 URL의 수명을 제한한다(스펙 §4-3-8 — 10분). */
        val PRESIGN_EXPIRY: Duration = Duration.ofMinutes(10)
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
