package com.knk.manyak.story.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.image.service.UploadedImageKind
import com.knk.manyak.image.service.UploadedImageObjectKeys
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 스토리 이미지 업로드의 공통 게이트(KNK-1126, 스펙 §4-3-8) — 소유권 판정, 인물 조회, 업로드 객체 검증.
 *
 * [StoryImageService]와 [CharacterImageAdder]가 함께 쓴다. 둘로 나뉜 이유는 유니크 위반을 트랜잭션 밖에서
 * 409로 바꾸기 위해서인데(같은 클래스 자기 호출은 프록시를 안 탄다), 그 둘이 서로를 참조하면 순환이 된다.
 * 공통 부분을 여기로 빼 순환 없이 공유한다.
 */
@Component
class StoryImageAccess(
    private val storyRepository: StoryRepository,
    private val userRepository: UserRepository,
    private val storyCharacterRepository: StoryCharacterRepository,
    private val uploadedImageStorage: UploadedImageStorage,
) {

    /**
     * 회원 소유 스토리를 연다. 없음·삭제는 404, 게스트 소유는 400(이관 뒤에 올린다), 타인 소유는 403이다.
     * 404를 403보다 먼저 내 존재 여부를 노출하지 않는다(스토리 삭제와 같은 순서).
     */
    fun resolveOwnedStory(storyId: String, userId: Long): Story {
        val story = storyRepository.findByPublicIdAndDeletedAtIsNull(parsePublicIdOrNull(storyId) ?: notFoundStory())
            ?: notFoundStory()
        requireUploadableOwner(story, userId)
        return story
    }

    /** 이 스토리의 인물. 형식 오류·없음은 404로 통일한다(IDOR 차단). */
    fun resolveCharacter(story: Story, characterId: String): StoryCharacter {
        val publicId = parsePublicIdOrNull(characterId) ?: notFoundCharacter()
        return storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)
            .firstOrNull { it.publicId == publicId }
            ?: notFoundCharacter()
    }

    /**
     * 연결 요청의 객체 키를 검증하고 서빙 URL을 만든다. 표지·인물이 같은 규칙을 쓴다.
     *
     * 순서가 중요하다: prefix를 **먼저** 보고(남의 스토리·프리셋 키를 HEAD로 찔러 존재를 알아내지 못하게)
     * 그다음 객체를 확인한다. presign 서명이 형식·크기를 고정하지만, 서명 없이 올라온 객체나 재사용된 키가
     * 있을 수 있어 신뢰 경계에서 한 번 더 본다.
     */
    fun resolveUploadedUrl(
        story: Story,
        ownerPublicId: UUID?,
        kind: UploadedImageKind,
        objectKey: String,
    ): String =
        resolveUploadedUrlUnder(
            // 스토리 경로와 **소유자의 draft 경로**를 모두 받는다(KNK-1391). 웹이 제작·수정 화면에서 같은
            // 업로드 컴포넌트를 쓰면 수정 중에도 draft 키가 올라오는데, 내가 올린 객체라면 막을 이유가 없다.
            expectedPrefixes = listOfNotNull(
                "${UploadedImageObjectKeys.prefixOf(kind, story.publicId)}/",
                ownerPublicId?.let { "${UploadedImageObjectKeys.draftPrefixOf(kind, it)}/" },
            ),
            objectKey = objectKey,
            mismatchMessage = "내가 이 스토리에 올린 업로드 이미지가 아닙니다.",
        )

    /**
     * 등록 전 업로드(일반 제작, KNK-1390)의 객체 키 검증. 스토리가 아직 없으니 소유는 사용자 공개 식별자로
     * 가른다. 그 밖의 규칙(HEAD 존재·5MB·형식)은 스토리 스코프와 같다.
     */
    fun resolveDraftUploadedUrl(userPublicId: UUID, kind: UploadedImageKind, objectKey: String): String =
        resolveUploadedUrlUnder(
            expectedPrefixes = listOf("${UploadedImageObjectKeys.draftPrefixOf(kind, userPublicId)}/"),
            objectKey = objectKey,
            mismatchMessage = "내가 올린 업로드 이미지가 아닙니다.",
        )

    /** 업로드 이미지를 쓸 수 있는 회원의 공개 식별자. 토큰은 유효하나 사용자가 사라졌으면 401이다. */
    fun resolveUserPublicId(userId: Long): UUID =
        userRepository.findById(userId).orElse(null)?.publicId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")

    private fun resolveUploadedUrlUnder(
        expectedPrefixes: List<String>,
        objectKey: String,
        mismatchMessage: String,
    ): String {
        requireUploadEnabled()
        if (expectedPrefixes.none { objectKey.startsWith(it) }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, mismatchMessage)
        }
        val uploaded = uploadedImageStorage.head(objectKey)
            ?: throw CodedResponseStatusException(
                HttpStatus.BAD_REQUEST,
                ApiErrorCodes.UPLOAD_NOT_FOUND,
                "업로드된 이미지를 찾을 수 없습니다. 업로드를 마친 뒤 다시 시도해 주세요.",
            )
        if (uploaded.contentLength > UploadedImageObjectKeys.MAX_CONTENT_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지는 5MB를 넘을 수 없습니다.")
        }
        requireSupportedContentType(uploaded.contentType)
        // 자동 검수 훅 자리(KNK-1160~ 도입 시) — 표지·인물 연결이 모두 여기를 지나므로 한 곳이면 된다.
        return uploadedImageStorage.serveUrlOf(objectKey) ?: throw uploadDisabled()
    }

    fun requireUploadEnabled() {
        if (!uploadedImageStorage.isEnabled()) {
            throw uploadDisabled()
        }
    }

    fun requireSupportedContentType(contentType: String?): String {
        if (contentType == null || contentType !in UploadedImageObjectKeys.EXTENSION_BY_CONTENT_TYPE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 이미지 형식입니다(JPEG·PNG·WebP).")
        }
        return contentType
    }

    private fun requireUploadableOwner(story: Story, userId: Long) {
        // 게스트 소유는 이관 뒤에 올린다 — 소유자가 없으면 누구의 이미지인지 책임 주체가 없다.
        if (story.userId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "로그인 후 내 스토리로 가져와야 이미지를 올릴 수 있습니다.",
            )
        }
        if (story.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "스토리를 수정할 권한이 없습니다.")
        }
    }

    private fun uploadDisabled() =
        ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이미지 업로드가 설정되지 않았습니다.")

    private fun notFoundStory(): Nothing =
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")

    private fun notFoundCharacter(): Nothing =
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "인물을 찾을 수 없습니다.")

    companion object {
        fun parsePublicIdOrNull(value: String): UUID? =
            try {
                UUID.fromString(value)
            } catch (ignored: IllegalArgumentException) {
                null
            }

        /** 같은 인물 안에서 이름이 겹쳤다. 사전 조회와 유니크 위반이 같은 409로 모인다. */
        fun duplicateImageName(cause: Throwable? = null) = ResponseStatusException(
            HttpStatus.CONFLICT,
            "같은 이름의 이미지가 이미 있습니다.",
            cause,
        )
    }
}
