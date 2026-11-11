package com.knk.manyak.story.service

import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.global.observability.aicall.RecordedAiCall
import com.knk.manyak.story.client.AiCharacterAppearance
import com.knk.manyak.story.client.AiCharacterImage
import com.knk.manyak.story.client.AiStoryCompileResponse
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.dto.StoryCreationSessionStatus
import com.knk.manyak.story.dto.StoryCreationTagSource
import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.dto.StoryVisibility
import com.knk.manyak.story.dto.toEndingResponse
import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryCharacterImage
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryLorebook
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.event.StoryIndexRequestedEvent
import com.knk.manyak.story.repository.StoryCharacterImageRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryLorebookRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import com.knk.manyak.story.service.GeneratedStoryImageUploader.UploadedImage
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 간편 제작 완성의 저장(KNK-2056에서 옛 SimpleStoryCreationService에서 추출, 호출자는 [SimpleStoryCompletionService]). 진행 행을 잠그고 완성 스토리 묶음을 한 트랜잭션에
 * 저장하며, 이미 저장된 묶음에서 같은 완성 응답을 다시 만든다. 트랜잭션은 전처럼 [TransactionTemplate]이다(모든 예외에 롤백).
 */
@Component
class SimpleStoryCompletionWriter(
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val storyCreationSessionTagRepository: StoryCreationSessionTagRepository,
    private val storyCreationStorylineRepository: StoryCreationStorylineRepository,
    private val storyRepository: StoryRepository,
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyLorebookRepository: StoryLorebookRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val storyCharacterRepository: StoryCharacterRepository,
    // 인물 이미지 정본(KNK-1126, V76). 옛 컬럼과 함께 채운다(두 릴리스 규칙).
    private val storyCharacterImageRepository: StoryCharacterImageRepository,
    private val storyPublicSnapshotService: StoryPublicSnapshotService,
    // 검색 색인 요청(StoryIndexRequestedEvent)은 저장 트랜잭션 안에서 발행한다. 리스너는 커밋 뒤에만 돈다.
    private val eventPublisher: ApplicationEventPublisher,
    private val aiCallRecorder: AiCallRecorder,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * 진행 행을 잠그고 완성 스토리 묶음을 한 트랜잭션에 저장한다(KNK-2056에서 [SimpleStoryCompletionService.compileAndPersist]의
     * 트랜잭션 본문을 옮김). 잠금 순서, 검색 색인 이벤트 발행(트랜잭션 안), 호출 기록 연결은 그대로다.
     */
    internal fun persist(
        session: StoryCreationSession,
        attributedUserId: Long?,
        visibility: StoryVisibility,
        selectedStoryline: StoryCreationStoryline,
        genreTags: List<StoryCreationTag>,
        selectedLorebooks: List<Lorebook>,
        requestId: UUID,
        recorded: RecordedAiCall<AiStoryCompileResponse>,
        protagonistName: String?,
        genre: String?,
        storyPublicId: UUID,
        uploadedThumbnail: UploadedImage?,
        uploadedImages: Map<String, UploadedImage>,
        appearancesByName: Map<String, AiCharacterAppearance>,
        imagesByName: Map<String, AiCharacterImage>,
        descriptionsByName: Map<String, String?>,
    ): StoryCreationOutcome {
        val aiResponse = recorded.result
        return transactionTemplate.execute {
            val lockedSession = storyCreationSessionRepository.findByIdForUpdate(session.id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "간편 제작 진행 정보를 찾을 수 없습니다.")
            if (lockedSession.status == StoryCreationSessionStatus.STORY_CREATED) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "이미 스토리가 생성된 간편 제작 진행입니다.")
            }

            selectedStoryline.isSelected = true
            storyCreationStorylineRepository.save(selectedStoryline)

            val story = storyRepository.save(
                Story(
                    publicId = storyPublicId,
                    userId = attributedUserId,
                    title = UsernameTokenRenderer.truncateRaw(aiResponse.stories.title, STORY_TITLE_MAX_LENGTH),
                    protagonistName = protagonistName,
                    oneLineIntro = UsernameTokenRenderer.truncateRaw(aiResponse.stories.oneLineIntro, STORY_ONE_LINE_INTRO_MAX_LENGTH),
                    description = aiResponse.stories.description,
                    genre = genre,
                    // 컴파일 표지 업로드가 성공하면 URL을 저장하고, 실패하면 표지 없이 등록한다.
                    thumbnailImageUrl = uploadedThumbnail?.url,
                    // 선택한 공개 범위를 그대로 저장한다. 생략한 요청은 DTO 기본값 PUBLIC이다.
                    visibility = visibility,
                ),
            )
            storySettingRepository.save(
                StorySetting(
                    story = story,
                    worldSetting = aiResponse.storySettings.worldSetting,
                    characterSetting = aiResponse.storySettings.characterSetting,
                    userRoleSetting = aiResponse.storySettings.userRoleSetting,
                    ruleSetting = aiResponse.storySettings.ruleSetting,
                ),
            )
            val startSetting = storyStartSettingRepository.save(
                StoryStartSetting(
                    story = story,
                    name = UsernameTokenRenderer.truncateRaw(aiResponse.storyStartSettings.name, STORY_START_SETTING_NAME_MAX_LENGTH),
                    prologue = aiResponse.storyStartSettings.prologue,
                    startSituation = aiResponse.storyStartSettings.startSituation,
                ),
            )
            val savedSuggestedInputs = storySuggestedInputRepository.saveAll(
                aiResponse.storySuggestedInputs.mapIndexed { index, inputText ->
                    StorySuggestedInput(
                        startSetting = startSetting,
                        inputText = inputText,
                        inputOrder = (index + 1).toShort(),
                    )
                },
            ).map { it.inputText }

            // 전달한 로어북을 스토리에 연결한다(sort_order 1-based, ck_story_lorebooks_sort_order > 0).
            if (selectedLorebooks.isNotEmpty()) {
                storyLorebookRepository.saveAll(
                    selectedLorebooks.mapIndexed { index, lorebook ->
                        StoryLorebook(
                            story = story,
                            lorebook = lorebook,
                            sortOrder = (index + 1).toShort(),
                        )
                    },
                )
            }

            // 컴파일 산출물의 주요 사건(스토리 소유, sort_order 0-based)을 저작 경로와 같은 테이블에 저장한다.
            if (aiResponse.storyMainEvents.isNotEmpty()) {
                // 저장 이름(방어적 절단 후)이 스토리 안에서 유니크여야 이름 기반 완결·목표 매칭이 무모호하다.
                // 중복은 AI 응답의 결함이므로 400이 아니라 502(불완전 AI 응답)로 처리하고 저장을 롤백한다(엔딩과 동일).
                val mainEventNames = aiResponse.storyMainEvents.map { UsernameTokenRenderer.truncateRaw(it.name, STORY_MAIN_EVENT_NAME_MAX_LENGTH) }
                if (mainEventNames.size != mainEventNames.toSet().size) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 컴파일 응답의 주요 사건 이름이 중복됩니다.")
                }
                storyMainEventRepository.saveAll(
                    aiResponse.storyMainEvents.mapIndexed { index, item ->
                        StoryMainEvent(
                            story = story,
                            name = mainEventNames[index],
                            description = item.description,
                            keySentence = item.keySentence,
                            sortOrder = index.toShort(),
                        )
                    },
                )
            }

            // 컴파일 산출물의 엔딩(시작 설정 스코프, sort_order 1-based, ck_story_endings_order > 0)을 저장한다.
            val savedEndings = if (aiResponse.storyEndings.isEmpty()) {
                emptyList()
            } else {
                // 저장 이름(방어적 절단 후)이 시작 설정 안에서 유니크여야 이름 기반 도달 매칭이 무모호하다(제작·수정과 동일 불변식).
                // 중복은 사용자 입력이 아니라 AI 응답의 결함이므로 400이 아니라 502(불완전 AI 응답)로 처리하고 저장을 롤백한다.
                val endingNames = aiResponse.storyEndings.map { UsernameTokenRenderer.truncateRaw(it.name, STORY_ENDING_NAME_MAX_LENGTH) }
                if (endingNames.size != endingNames.toSet().size) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 컴파일 응답의 엔딩 이름이 중복됩니다.")
                }
                storyEndingRepository.saveAll(
                    aiResponse.storyEndings.mapIndexed { index, item ->
                        StoryEnding(
                            startSetting = startSetting,
                            name = endingNames[index],
                            minTurns = item.minTurns,
                            achievementCondition = item.achievementCondition,
                            epilogue = item.epilogue,
                            sortOrder = (index + 1).toShort(),
                        )
                    },
                ).toList()
            }

            persistStoryCharacters(story, appearancesByName, imagesByName.keys, uploadedImages, descriptionsByName)

            // PUBLIC으로 완성하면 게스트도 저장 트랜잭션에서 마지막 공개 스냅샷을 만든다.
            // 검색 문서의 목록 노출 여부는 회원 소유 여부까지 확인한다.
            storyPublicSnapshotService.refresh(story)
            eventPublisher.publishEvent(StoryIndexRequestedEvent(story.id))

            // 익명 세션을 로그인 사용자가 완료(claim)하면 세션 소유자도 그 사용자로 박는다 — 안 그러면 그 스토리의
            // 스토리라인 평가 소유권 검사(session.userId 기반)가 세션을 익명으로 보아 아무나 평가/취소할 수 있다(Codex PR #76 P2).
            lockedSession.userId = attributedUserId
            lockedSession.status = StoryCreationSessionStatus.STORY_CREATED
            lockedSession.storyId = story.id
            // 회수 재실행 검증용 바인딩(KNK-644): 이 세션을 만든 요청의 request_id를 STORY_CREATED와 원자적으로 커밋한다.
            lockedSession.creationRequestId = requestId
            storyCreationSessionRepository.save(lockedSession)
            // 스토리·세션과 같은 트랜잭션으로 연결한다. 저장 실패 시 두 호출의 story_id도 NULL로 남는다.
            aiCallRecorder.attachStoryId(recorded.aiCallLogId, story.id)
            lockedSession.storylineAiCallLogId?.let { aiCallRecorder.attachStoryId(it, story.id) }

            StoryCreationOutcome(
                response = SimpleStoryCreateResponse(
                    id = story.publicId.toString(),
                    title = story.title,
                    oneLineIntro = story.oneLineIntro,
                    description = story.description,
                    genres = genreTags.map { it.name },
                    // 간편 제작은 시작 설정 1개다(KNK-515 복수화). 추천 입력·엔딩을 그 시작 설정에 종속시킨다.
                    startSettings = listOf(
                        StoryStartSettingResponse(
                            id = startSetting.publicId.toString(),
                            name = startSetting.name,
                            prologue = startSetting.prologue,
                            startSituation = startSetting.startSituation,
                            suggestedInputs = savedSuggestedInputs,
                            endings = savedEndings.map { it.toEndingResponse() },
                        ),
                    ),
                ).rendered(story.protagonistName),
                aiCallLogId = recorded.aiCallLogId,
            )
        } ?: throw IllegalStateException("Story creation transaction result is empty")
    }

    /**
     * 회수 재실행(P2-10, KNK-635/644)이 이미 STORY_CREATED인 세션을 만나면, [StoryCreationSession.storyId]가 가리키는
     * 저장된 스토리로 원 POST 응답([SimpleStoryCreateResponse])을 재구성한다. [compileAndPersist]가 만든 응답과 같은 모양이며,
     * AI·저장을 다시 타지 않는다. 검증(회수 여부 + `creationRequestId == request_id` 바인딩)은 호출부([doCreateSimpleStory])가
     * 선행하므로, 소유자 없는 게스트 세션도 이 세션을 만든 요청에만 안전하게 열린다(Codex P1 바인딩).
     *
     * storyId·자식 행이 없으면(비정상 상태) 원래의 409로 폴백한다.
     */
    internal fun reconcileCreatedSession(session: StoryCreationSession): StoryCreationOutcome {
        val alreadyCreated = ResponseStatusException(HttpStatus.CONFLICT, "이미 스토리가 생성된 간편 제작 진행입니다.")
        val storyId = session.storyId ?: throw alreadyCreated
        val story = storyRepository.findById(storyId).orElseThrow { alreadyCreated }
        val startSetting = storyStartSettingRepository.findFirstByStoryIdOrderByIdAsc(storyId) ?: throw alreadyCreated

        val suggestedInputs = storySuggestedInputRepository
            .findByStartSettingIdOrderByInputOrderAsc(startSetting.id)
            .map { it.inputText }
        val endings = storyEndingRepository
            .findByStartSettingIdAndEnabledTrueOrderBySortOrderAsc(startSetting.id)
            .map { it.toEndingResponse() }
        // 응답의 genres는 세션 장르 태그로 재구성한다 — compile 경로와 같은 selectGenreTags를 써 순서를 일치시킨다(KNK-861).
        val genres = selectGenreTags(storyCreationSessionTagRepository.findAllWithTagByCreationSessionId(session.id))
            .map { it.name }

        return StoryCreationOutcome(
            response = SimpleStoryCreateResponse(
                id = story.publicId.toString(),
                title = story.title,
                oneLineIntro = story.oneLineIntro,
                description = story.description,
                genres = genres,
                startSettings = listOf(
                    StoryStartSettingResponse(
                        id = startSetting.publicId.toString(),
                        name = startSetting.name,
                        prologue = startSetting.prologue,
                        startSituation = startSetting.startSituation,
                        suggestedInputs = suggestedInputs,
                        endings = endings,
                    ),
                ),
            ).rendered(story.protagonistName),
            aiCallLogId = null,
        )
    }

    /**
     * 컴파일 산출물의 인물을 `story_characters`에 저장한다(스펙 §4-4 · §5-3-3).
     *
     * 이름은 [normalizeByName]이 이미 정규화·중복 제거했다. `character_appearances`는 인물 전원,
     * `character_images`는 외형이 있는 인물만이라 길이가 다를 수 있어 이름으로 매칭하며, 이미지만 있고 외형이
     * 없는 이름도(계약상 없어야 하지만) 행을 만들어 업로드한 이미지를 잃지 않는다.
     */
    private fun persistStoryCharacters(
        story: Story,
        appearancesByName: Map<String, AiCharacterAppearance>,
        imageNames: Set<String>,
        uploadedImages: Map<String, UploadedImage>,
        descriptionsByName: Map<String, String?>,
    ) {
        val names = (appearancesByName.keys + imageNames).toList()
        if (names.isEmpty()) {
            return
        }
        val characters = storyCharacterRepository.saveAll(
            names.map { name ->
                val appearance = appearancesByName[name]
                StoryCharacter(
                    story = story,
                    name = name,
                    description = descriptionsByName[name]?.trim()?.ifEmpty { null },
                    // 옛 컬럼에도 계속 쓴다(KNK-1126) — 읽는 코드는 새 테이블로 옮겼지만, 롤백하면 이 컬럼을
                    // 다시 읽으므로 컬럼 DROP 전까지 둘 다 채운다(계약 마이그레이션 두 릴리스 규칙).
                    imageUrl = uploadedImages[name]?.url,
                    gender = appearance?.gender,
                    age = appearance?.age,
                    body = appearance?.body,
                    face = appearance?.face,
                    hair = appearance?.hair,
                    outfit = appearance?.outfit,
                    visualIdentity = appearance?.visualIdentity,
                )
            },
        )
        // 새 정본에도 함께 넣는다(KNK-1126, V76). 컴파일이 만든 첫 장의 이름은 `{인물이름}_기본`이다 —
        // 상세의 대표 이미지 판정과 소유자가 나중에 올리는 `{이름}_웃음` 같은 이름이 같은 규칙을 쓴다.
        val firstImages = characters.mapNotNull { character ->
            uploadedImages[character.name]?.url?.let { url ->
                StoryCharacterImage(
                    character = character,
                    imageName = StoryCharacterImage.defaultImageNameOf(character.name),
                    imageUrl = url,
                    sortOrder = 0,
                )
            }
        }
        if (firstImages.isNotEmpty()) {
            storyCharacterImageRepository.saveAll(firstImages)
        }
    }

    private companion object {
        // AI 응답이 컬럼 길이를 초과해도 트랜잭션이 실패하지 않도록 방어적으로 자른다. (stories 컬럼 정의와 일치)
        const val STORY_TITLE_MAX_LENGTH = 100
        const val STORY_ONE_LINE_INTRO_MAX_LENGTH = 255

        // 시작 설정·주요 사건·엔딩 이름 컬럼(VARCHAR(100)) 초과를 방어적으로 자른다.
        const val STORY_START_SETTING_NAME_MAX_LENGTH = 100
        const val STORY_MAIN_EVENT_NAME_MAX_LENGTH = 100
        const val STORY_ENDING_NAME_MAX_LENGTH = 100
    }
}

/**
 * 세션 태그 행에서 장르 태그를 선별한다(카테고리 필터 + 정규화 키 중복 제거 + 정렬).
 * compile 경로와 완성 회수 경로가 공유한다 — 규칙이 복붙돼 한쪽만 고쳐지면 회수 응답 순서가 최초 응답과 어긋난다(KNK-861).
 *
 * 장르는 세션 스코프(character 없음). 스토리라인 경로와 같은 정규화 키 기준으로 중복 제거한다.
 * 정렬 1순위가 tagSource인 이유(KNK-859): CUSTOM은 sortOrder 기본값이 0이라 이 기준이 없으면 직접 입력 장르가
 * 시드 sortOrder를 가진 제공 장르를 앞지른다. 그러면 스토리라인 응답의 '사전 정의 → 직접 입력' 순서와 어긋난다.
 */
internal fun selectGenreTags(sessionTagRows: List<StoryCreationSessionTag>): List<StoryCreationTag> = sessionTagRows
    .map { it.tag }
    .filter { it.category == SimpleStoryTagCategory.GENRE }
    .distinctBy { it.normalizedName }
    .sortedWith(compareBy({ it.tagSource == StoryCreationTagSource.CUSTOM }, { it.sortOrder }, { it.id }))

internal data class StoryCreationOutcome(
    val response: SimpleStoryCreateResponse,
    // 회수 재실행이 저장된 스토리를 재구성해 돌려주는 경로(P2-10)는 AI를 호출하지 않으므로 null이다.
    val aiCallLogId: Long?,
)
