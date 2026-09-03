package com.knk.manyak.story.service

import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.global.security.isOwnerAccessAllowed
import com.knk.manyak.story.dto.GeneralStartSettingInput
import com.knk.manyak.story.dto.StoryEditFormResponse
import com.knk.manyak.story.dto.StoryEditSettingsResponse
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.dto.toMainEventResponse
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.entity.StoryVisibility
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
 * 스토리 수정(스펙 §4-3-8, KNK-404): 수정 폼 조회(GET /stories/{id}/edit)와 부분 갱신(PATCH /stories/{id}).
 *
 * 소유권(§4-5): user_id가 NULL인 게스트 스토리는 익명 허용(현행 유지), 회원 소유 스토리는 소유자만 접근하고
 * 불일치·미인증이면 403이다. 간편·일반 제작 방식과 무관하게 같은 계약으로 수정한다. 이미지는 §4-3-9 범위라 제외한다.
 */
@Service
class StoryEditService(
    private val storyRepository: StoryRepository,
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val startSettingResponseAssembler: StartSettingResponseAssembler,
    private val storyPublicSnapshotService: StoryPublicSnapshotService,
    private val suspensionGuard: SuspensionGuard,
) {

    @Transactional(readOnly = true)
    fun getEditForm(storyId: String, userId: Long?): StoryEditFormResponse {
        val story = resolveStory(storyId)
        requireOwnerAccess(story, userId)
        return buildEditForm(story)
    }

    /** 부분 갱신: 보낸(non-null) 필드만 교체하고 나머지는 유지한다. 리스트는 보내면 전체 교체다. */
    @Transactional
    fun updateStory(storyId: String, userId: Long?, request: UpdateStoryRequest): StoryEditFormResponse {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499). 공개 전환 포함 수정 전반이 대상.
        // 쓰기 락으로 스토리 애그리거트를 잠가 동시 PATCH의 자식 리스트 교체 경합을 직렬화한다.
        val story = resolveStoryForUpdate(storyId)
        requireOwnerAccess(story, userId)
        requirePublishedForVisibilityChange(story, request.visibility)
        // 게스트(소유자 없음) 스토리의 공개 전환은 막는다(KNK-149). 값이 그대로 실려 오는 폼 왕복은 전환이
        // 아니므로 통과시킨다(위 requirePublishedForVisibilityChange와 같은 이유 — 레거시 PUBLIC 게스트
        // 스토리가 폼 저장 자체를 못 하게 되면 안 된다).
        requireOwnerCanPublish(story.userId, request.visibility?.takeIf { it != story.visibility })

        // 기본 정보 — 보낸 필드만 교체. 제목·한 줄 소개는 present-only 비어있음 검증(제작과 동일 계약).
        request.title?.let {
            if (it.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "제목은 비어 있을 수 없습니다.")
            story.title = it
        }
        request.oneLineIntro?.let {
            if (it.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "한 줄 소개는 비어 있을 수 없습니다.")
            story.oneLineIntro = it
        }
        request.description?.let { story.description = it }
        request.genres?.let { story.genre = it.joinToString(separator = ", ").ifBlank { null } }
        // 공개 전환(KNK-1021). 전환 가능 여부는 위 requirePublishedForVisibilityChange가 이미 확정했다.
        request.visibility?.let { story.visibility = it }

        // 스토리 설정 통글 4필드 — 없으면 생성, 있으면 교체(제작 시 생성되므로 보통 존재).
        request.storySettings?.let { input ->
            val setting = storySettingRepository.findByStoryId(story.id) ?: StorySetting(story = story)
            setting.worldSetting = input.worldSetting
            setting.characterSetting = input.characterSetting
            setting.userRoleSetting = input.userRoleSetting
            setting.ruleSetting = input.ruleSetting
            storySettingRepository.save(setting)
        }

        // 주요 사건 전체 교체(sort_order 0-based, 스토리 스코프).
        request.mainEvents?.let { events ->
            requireDistinctMainEventNames(events.map { it.name })
            val existing = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)
            storyMainEventRepository.deleteAll(existing)
            storyMainEventRepository.flush()
            storyMainEventRepository.saveAll(
                events.mapIndexed { index, item ->
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

        // 시작 설정 전체 교체(KNK-515 복수화). 추천 입력·엔딩은 각 시작 설정에 종속되므로 함께 동기화한다.
        request.startSettings?.let { inputs -> syncStartSettings(story, inputs) }

        // 자식 교체까지 모두 끝난 뒤에 "마지막 공개 버전" 스냅샷을 갱신한다(KNK-1065). 공개 상태가 아니면 no-op이라
        // 비공개 개작은 스냅샷에 들어가지 않는다. 여기가 스토리 애그리거트를 바꾸는 유일한 수정 경로다.
        storyPublicSnapshotService.refresh(story)

        return buildEditForm(story)
    }

    /**
     * 시작 설정 컬렉션을 요청과 동기화한다(전체 교체). 각 원소의 id(공개 식별자)가 기존과 일치하면 in-place 갱신해
     * 시작 설정 행 identity를 보존하고(진행 중 채팅의 start_setting_id 참조 유지), 없으면 신규 추가, 요청에서 빠진
     * 기존은 자식(추천 입력·엔딩)과 함께 삭제한다. 삭제된 시작 설정을 참조하던 채팅은 FK(ON DELETE SET NULL)로 해제된다.
     * 존재하지 않거나 이 스토리 소속이 아닌 id를 지목하면 400이다(조용한 무시 금지).
     */
    private fun syncStartSettings(story: Story, inputs: List<GeneralStartSettingInput>) {
        val existing = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id)
        val existingByPublicId = existing.associateBy { it.publicId }

        // 요청 내 시작 설정 id 중복은 전체 교체를 모호하게 만든다: 같은 행을 두 번 덮어 뒤엣것만 남고 앞엣것이 조용히 유실된다.
        // 엔딩·주요 사건 이름 유니크와 같은 불변식으로, 중복 id는 저장하지 않고 400으로 거부한다(silent wipe 방지).
        val requestedPublicIds = inputs.mapNotNull { it.id?.let(::parseStartSettingPublicIdOrNull) }
        if (requestedPublicIds.size != requestedPublicIds.toSet().size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "시작 설정 ID는 요청 내에서 중복될 수 없습니다.")
        }

        // 각 입력을 기존 시작 설정(id 매칭) 또는 신규(null)로 해소한다. 매칭 안 되는 id는 400(없거나 이 스토리 소속 아님).
        val resolved = inputs.map { input ->
            val match = input.id?.let { raw ->
                val publicId = parseStartSettingPublicIdOrNull(raw)
                existingByPublicId[publicId]
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이 스토리에 속하지 않는 시작 설정 ID입니다.")
            }
            input to match
        }
        val keptIds = resolved.mapNotNull { it.second?.id }.toSet()

        // 요청에서 빠진 기존 시작 설정은 자식(추천 입력·엔딩)까지 삭제한다.
        existing.filter { it.id !in keptIds }.forEach { removed ->
            storySuggestedInputRepository.deleteByStartSettingId(removed.id)
            storyEndingRepository.deleteByStartSettingId(removed.id)
            storyStartSettingRepository.delete(removed)
        }
        storyStartSettingRepository.flush()

        // upsert: 매칭이면 in-place 갱신, 없으면 신규. 각 시작 설정의 추천 입력·엔딩은 전체 교체한다.
        resolved.forEach { (input, match) ->
            val startSetting = (match ?: StoryStartSetting(story = story)).apply {
                name = input.name
                prologue = input.prologue
                startSituation = input.startSituation
            }
            val saved = storyStartSettingRepository.save(startSetting)
            replaceStartSettingChildren(saved, input)
        }
    }

    /** 시작 설정 하나의 추천 입력·엔딩을 전체 교체한다. 벌크 DELETE는 즉시 실행돼 재삽입과 유니크 충돌하지 않는다. */
    private fun replaceStartSettingChildren(startSetting: StoryStartSetting, input: GeneralStartSettingInput) {
        // 추천 입력 전체 교체(input_order 1-based).
        storySuggestedInputRepository.deleteByStartSettingId(startSetting.id)
        storySuggestedInputRepository.saveAll(
            input.suggestedInputs.mapIndexed { index, text ->
                StorySuggestedInput(startSetting = startSetting, inputText = text, inputOrder = (index + 1).toShort())
            },
        )
        // 엔딩 전체 교체(sort_order 1-based). 이름 유니크(시작 설정 내). 레거시(enabled=false)까지 지워 유니크 충돌을 피한다.
        requireDistinctEndingNames(input.endings.map { it.name })
        storyEndingRepository.deleteByStartSettingId(startSetting.id)
        if (input.endings.isNotEmpty()) {
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
            )
        }
    }

    /** 시작 설정 공개 식별자(UUID 문자열)를 파싱한다. 형식 오류는 null로 반환해 호출부에서 400 처리한다. */
    private fun parseStartSettingPublicIdOrNull(raw: String): UUID? =
        try {
            UUID.fromString(raw)
        } catch (ignored: IllegalArgumentException) {
            null
        }

    private fun buildEditForm(story: Story): StoryEditFormResponse {
        val setting = storySettingRepository.findByStoryId(story.id)
        // 시작 설정 복수화(KNK-515): 등록 순서로 전부 싣고, 추천 입력·엔딩은 각 시작 설정에 종속시킨다.
        val startSettings = startSettingResponseAssembler.assemble(story.id)
        val mainEvents = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)
            .map { it.toMainEventResponse() }

        return StoryEditFormResponse(
            title = story.title,
            oneLineIntro = story.oneLineIntro,
            description = story.description,
            genres = story.toGenreNames(),
            storySettings = StoryEditSettingsResponse(
                worldSetting = setting?.worldSetting,
                characterSetting = setting?.characterSetting,
                userRoleSetting = setting?.userRoleSetting,
                ruleSetting = setting?.ruleSetting,
            ),
            startSettings = startSettings,
            mainEvents = mainEvents,
            visibility = story.visibility,
        )
    }

    /**
     * 공개 범위 전환 게이트(KNK-1021). PUBLISHED가 아닌 스토리의 공개 범위 **변경**을 400으로 거부한다.
     *
     * 읽기 가시성이 `PUBLISHED && PUBLIC`(§4-3-1)이라 DRAFT에 PUBLIC을 저장하면 저장값은 공개인데 아무도 못 읽는
     * 모순 상태가 남는다. 이 API는 편집이지 발행이 아니므로 status를 함께 올리지 않고 전환 자체를 거부한다
     * (등록 경로가 status를 항상 PUBLISHED로 저장해 — 초안 저장 경로 없음, §4-3-8 — 실제 대상은 레거시 데이터다).
     *
     * **값이 같으면 통과시킨다.** 수정 폼 응답이 visibility를 싣기 때문에(폼 왕복) 프론트가 전체 폼을 되돌려보내면
     * 값이 그대로 실려 오는데, 전환이 아닌 이 무변경 전송까지 막으면 DRAFT 스토리는 폼 저장 자체가 불가능해진다.
     */
    private fun requirePublishedForVisibilityChange(story: Story, requested: StoryVisibility?) {
        if (requested == null || requested == story.visibility) {
            return
        }
        if (story.status != StoryStatus.PUBLISHED) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "등록되지 않은(PUBLISHED가 아닌) 스토리는 공개 범위를 바꿀 수 없습니다.",
            )
        }
    }

    /**
     * 소유권 게이트(§4-5, KNK-480): 게스트 스토리는 게스트만, 소유 스토리는 소유자만 수정할 수 있다.
     * 회원의 NULL 소유(게스트) 스토리 수정도 차단한다(이관 후 접근). 위반 시 403.
     */
    private fun requireOwnerAccess(story: Story, userId: Long?) {
        if (!isOwnerAccessAllowed(story.userId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "스토리를 수정할 권한이 없습니다.")
        }
    }

    /** 공개 식별자(UUID 문자열)로 스토리를 조회한다(조회용). 형식 오류·없음·삭제는 모두 404로 통일한다(IDOR 차단). */
    private fun resolveStory(publicId: String): Story =
        storyRepository.findByPublicIdAndDeletedAtIsNull(parsePublicId(publicId))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")

    /** 수정용 조회. 쓰기 락(PESSIMISTIC_WRITE)으로 동시 PATCH의 자식 리스트 교체 경합(유니크 충돌·유실)을 직렬화한다. */
    private fun resolveStoryForUpdate(publicId: String): Story =
        storyRepository.findByPublicIdAndDeletedAtIsNullForUpdate(parsePublicId(publicId))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")

    private fun parsePublicId(publicId: String): UUID =
        try {
            UUID.fromString(publicId)
        } catch (ignored: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }

    private fun Story.toGenreNames(): List<String> =
        genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
