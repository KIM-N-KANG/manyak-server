package com.knk.manyak.story.service

import org.springframework.context.ApplicationEventPublisher
import com.knk.manyak.search.event.StoryIndexRequestedEvent
import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.image.service.UploadedImageKind
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.dto.GeneralCharacterInput
import com.knk.manyak.story.dto.GeneralStartSettingInput
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.dto.toEndingResponse
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryCharacterImage
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.repository.StoryCharacterImageRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 일반 제작 승인 적용(스펙 §4-3-8). 검수 실행기가 승인한 입력으로 라이브 스토리를 만든다.
 *
 * 간편 제작과 달리 AI(컴파일)를 호출하지 않으므로 크레딧 소모·게스트 체험 한도 카운트가 없다. 저장하는 애그리거트
 * (스토리·설정·시작 설정·추천 입력·주요 사건·엔딩) 구조는 간편 제작 산출물과 같아, 이후 상세 조회·채팅 시작이
 * 제작 방식과 무관하게 동일하게 동작한다.
 */
@Service
class GeneralStoryCreationService(
    private val eventPublisher: ApplicationEventPublisher,
    private val storyRepository: StoryRepository,
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val storyCharacterRepository: StoryCharacterRepository,
    private val storyCharacterImageRepository: StoryCharacterImageRepository,
    private val storyImageAccess: StoryImageAccess,
    private val suspensionGuard: SuspensionGuard,
    private val storyThumbnailLinker: StoryThumbnailLinker,
    private val storyPublicSnapshotService: StoryPublicSnapshotService,
) {

    /**
     * 인증 선택(간편 제작과 동일 — 유효 토큰이면 [userId] 귀속, 익명이면 null). 응답은 간편 제작과 동일한
     * `{id, title, oneLineIntro, description, genres, startSetting}`이다.
     */
    @Transactional
    fun createGeneralStory(request: CreateGeneralStoryRequest, userId: Long?): SimpleStoryCreateResponse {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499).
        // 게스트는 공개(PUBLIC)를 지정할 수 없다(KNK-149). 조용히 PRIVATE으로 낮추지 않고 400으로 거부한다.
        requireOwnerCanPublish(ownerUserId = userId, requested = request.visibility)
        // 이미지는 회원만 올린다(KNK-1390). 키 검증은 스토리를 만들기 **전에** 끝낸다 — 잘못된 키로 만든
        // 스토리가 롤백에 기대 남지 않게, 그리고 S3 왕복을 저장 이전으로 모아 두기 위해서다.
        val uploaderPublicId = resolveUploaderPublicId(request, userId)
        val thumbnailImageUrl = request.thumbnailObjectKey?.let { objectKey ->
            storyImageAccess.resolveDraftUploadedUrl(requireNotNull(uploaderPublicId), UploadedImageKind.COVER, objectKey)
        }
        // 장르는 현행 방식대로 stories.genre에 쉼표 결합 저장한다(§4-3-8).
        val genre = request.genres.joinToString(separator = ", ").ifBlank { null }

        val story = storyRepository.save(
            Story(
                userId = userId,
                title = request.title,
                oneLineIntro = request.oneLineIntro,
                description = request.description,
                genre = genre,
                // 표지는 등록 시 1회 확정한다(§4-3-9). 후보가 없으면 null이고 프론트엔드가 placeholder를 그린다.
                thumbnailImageKey = storyThumbnailLinker.linkFor(request.genres),
                // 업로드 표지는 생성 표지와 같은 컬럼을 쓴다 — 노출 폴백 규칙(§4-3-9)이 그대로 적용된다.
                thumbnailImageUrl = thumbnailImageUrl,
                // 등록 = 발행(초안 개념 없음). 공개 범위는 요청 선택값(기본 PRIVATE).
                visibility = request.visibility,
            ),
        )
        storySettingRepository.save(
            StorySetting(
                story = story,
                worldSetting = request.storySettings.worldSetting,
                characterSetting = request.storySettings.characterSetting,
                userRoleSetting = request.storySettings.userRoleSetting,
                ruleSetting = request.storySettings.ruleSetting,
            ),
        )
        // 주요 사건은 스토리 스코프(sort_order 0-based). 이름은 스토리 내에서 유니크해야 한다
        // (이름 기반 런타임 식별, 완결·목표 매칭 모호성 방지 — KNK-523). 없으면 저장 생략.
        requireDistinctMainEventNames(request.mainEvents.map { it.name })
        if (request.mainEvents.isNotEmpty()) {
            storyMainEventRepository.saveAll(
                request.mainEvents.mapIndexed { index, item ->
                    StoryMainEvent(
                        story = story,
                        name = item.name,
                        description = item.description,
                        keySentence = item.keySentence,
                        sortOrder = index.toShort(),
                    )
                },
            )
        }

        persistCharacters(story, request.characters, uploaderPublicId)

        // 시작 설정별로 저장한다(KNK-515 복수화). 추천 입력·엔딩은 각 시작 설정 스코프다.
        val startSettingResponses = request.startSettings.map { input -> persistStartSetting(story, input) }

        // 공개(PUBLIC)로 등록하면 지금이 곧 마지막 공개 시점이다(KNK-1065). 비공개 등록이면 no-op이다.
        storyPublicSnapshotService.refresh(story)
        eventPublisher.publishEvent(StoryIndexRequestedEvent(story.id))

        return SimpleStoryCreateResponse(
            id = story.publicId.toString(),
            title = story.title,
            oneLineIntro = story.oneLineIntro,
            description = story.description,
            genres = request.genres,
            startSettings = startSettingResponses,
        )
    }

    /** 시작 설정 하나와 그 스코프의 추천 입력·엔딩을 저장하고 응답 객체로 만든다(KNK-515 복수화). */
    private fun persistStartSetting(story: Story, input: GeneralStartSettingInput): StoryStartSettingResponse {
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = input.name,
                prologue = input.prologue,
                startSituation = input.startSituation,
            ),
        )
        // 추천 입력은 시작 설정별 목록(1-based order). 채팅 시작 화면 계약과 동일하게 정확히 3개다.
        val suggestedInputs = storySuggestedInputRepository.saveAll(
            input.suggestedInputs.mapIndexed { index, text ->
                StorySuggestedInput(
                    startSetting = startSetting,
                    inputText = text,
                    inputOrder = (index + 1).toShort(),
                )
            },
        ).map { it.inputText }
        // 엔딩 이름은 시작 설정 내에서 유니크해야 한다(이름 기반 식별, 표시 모호성 방지 — KNK-523).
        requireDistinctEndingNames(input.endings.map { it.name })
        // 엔딩은 시작 설정 스코프(sort_order 1-based, ck_story_endings_order > 0). 없으면 저장 생략.
        val endings = if (input.endings.isEmpty()) {
            emptyList()
        } else {
            storyEndingRepository.saveAll(
                input.endings.mapIndexed { index, item ->
                    StoryEnding(
                        startSetting = startSetting,
                        name = item.name,
                        minTurns = item.requirement.minTurns,
                        achievementCondition = item.requirement.achievementCondition,
                        epilogue = item.epilogue,
                        sortOrder = (index + 1).toShort(),
                    )
                },
            ).map { it.toEndingResponse() }
        }
        return StoryStartSettingResponse(
            id = startSetting.publicId.toString(),
            name = startSetting.name,
            prologue = startSetting.prologue,
            startSituation = startSetting.startSituation,
            suggestedInputs = suggestedInputs,
            endings = endings,
        )
    }

    /** 제작 요청에는 수정용 매칭 키가 올 수 없다(가리킬 기존 행이 없다). 조용히 무시하면 이미지가 사라진다. */
    private fun requireNoExistingIds(input: GeneralCharacterInput) {
        if (input.id != null || input.images.orEmpty().any { it.id != null }) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "제작 요청에는 기존 인물·이미지 ID를 보낼 수 없습니다.",
            )
        }
    }

    /**
     * 이미지 필드를 쓸 수 있는 회원의 공개 식별자. 이미지가 없으면 null이고(게스트 등록 그대로 허용),
     * 이미지가 있는데 미인증이면 400이다 — 소유자가 없으면 올린 이미지의 책임 주체가 없다(스펙 §4-3-8).
     */
    private fun resolveUploaderPublicId(request: CreateGeneralStoryRequest, userId: Long?): UUID? {
        val hasImages = request.thumbnailObjectKey != null || request.characters.any { !it.images.isNullOrEmpty() }
        if (!hasImages) return null
        if (userId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "로그인 후 내 스토리로 가져와야 이미지를 올릴 수 있습니다.",
            )
        }
        return storyImageAccess.resolveUserPublicId(userId)
    }

    /**
     * 인물과 인물 이미지를 저장한다(KNK-1390). 인물 행 자체는 이미지가 아니라 게스트도 만들 수 있다 —
     * 이관 뒤에 이미지를 붙일 자리를 먼저 세운다. 이미지 규칙(이름 형식·10장 상한)은 등록 후 추가 경로와 같다.
     */
    private fun persistCharacters(story: Story, characters: List<GeneralCharacterInput>, uploaderPublicId: UUID?) {
        if (characters.isEmpty()) return
        requireDistinctCharacterNames(characters.map { it.name })
        characters.forEach { input ->
            // 제작에는 매칭할 기존 인물·이미지가 없다. id를 보냈다면 수정 요청을 잘못 보낸 것이라 400으로 돌려준다.
            requireNoExistingIds(input)
            val character = storyCharacterRepository.save(StoryCharacter(story = story, name = input.name))
            val images = input.images.orEmpty()
            if (images.isEmpty()) return@forEach
            val imageNames = images.map { requireValidImageName(character.name, requireNotNull(it.imageName)) }
            requireDistinctCharacterImageNames(imageNames)
            storyCharacterImageRepository.saveAll(
                images.mapIndexed { index, image ->
                    StoryCharacterImage(
                        character = character,
                        imageName = imageNames[index],
                        imageUrl = storyImageAccess.resolveDraftUploadedUrl(
                            requireNotNull(uploaderPublicId),
                            UploadedImageKind.CHARACTER,
                            requireNotNull(image.objectKey),
                        ),
                        // 표시 순서는 요청 배열 순서다(등록 후 추가와 같은 0-based).
                        sortOrder = index,
                    )
                },
            )
        }
    }
}
