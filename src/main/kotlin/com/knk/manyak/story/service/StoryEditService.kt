package com.knk.manyak.story.service

import org.springframework.context.ApplicationEventPublisher
import com.knk.manyak.search.event.StoryIndexRequestedEvent
import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.global.security.isOwnerAccessAllowed
import com.knk.manyak.story.dto.GeneralCharacterImageInput
import com.knk.manyak.story.dto.GeneralCharacterInput
import com.knk.manyak.story.dto.GeneralStartSettingInput
import com.knk.manyak.story.dto.StoryEditFormResponse
import com.knk.manyak.story.dto.StoryEditSettingsResponse
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.dto.toMainEventResponse
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryCharacterImage
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.image.service.ImageUrlResolver
import com.knk.manyak.image.service.UploadedImageKind
import com.knk.manyak.story.dto.CharacterImageResponse
import com.knk.manyak.story.dto.StoryEditCharacterResponse
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
 * 스토리 수정(스펙 §4-3-8, KNK-404): 수정 폼 조회(GET /stories/{id}/edit)와 부분 갱신(PATCH /stories/{id}).
 *
 * 소유권(§4-5): user_id가 NULL인 게스트 스토리는 익명 허용(현행 유지), 회원 소유 스토리는 소유자만 접근하고
 * 불일치·미인증이면 403이다. 간편·일반 제작 방식과 무관하게 같은 계약으로 수정한다. 이미지는 §4-3-9 범위라 제외한다.
 */
@Service
class StoryEditService(
    private val eventPublisher: ApplicationEventPublisher,
    private val storyRepository: StoryRepository,
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    // 이미지 업로드(KNK-1126): 편집 폼이 현재 표지·인물 이미지를 싣고, PATCH가 표지 교체를 받는다.
    private val storyCharacterRepository: StoryCharacterRepository,
    private val storyCharacterImageRepository: StoryCharacterImageRepository,
    private val storyImageAccess: StoryImageAccess,
    private val imageUrlResolver: ImageUrlResolver,
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
    fun updateStory(storyId: String, userId: Long?, request: UpdateStoryRequest, approvedImageUrls: Map<String, String> = emptyMap()): StoryEditFormResponse {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499). 공개 전환 포함 수정 전반이 대상.
        // 쓰기 락으로 스토리 애그리거트를 잠가 동시 PATCH의 자식 리스트 교체 경합을 직렬화한다.
        val story = resolveStoryForUpdate(storyId)
        requireOwnerAccess(story, userId)
        requirePublishedForVisibilityChange(story, request.visibility)
        // 게스트(소유자 없음) 스토리의 공개 전환은 막는다(KNK-149). 값이 그대로 실려 오는 폼 왕복은 전환이
        // 아니므로 통과시킨다(위 requirePublishedForVisibilityChange와 같은 이유 — 레거시 PUBLIC 게스트
        // 스토리가 폼 저장 자체를 못 하게 되면 안 된다).
        requireOwnerCanPublish(story.userId, request.visibility?.takeIf { it != story.visibility })

        // 공개 → 비공개로 내려가는 요청은 **바뀌기 전에** 한 번 캡처한다(PR #273 Codex P2). 아래 끝의
        // refresh는 이미 비공개라 no-op이라, 이 릴리스 전에 만들어진 스냅샷(인물 이미지가 없는 JSON)이
        // 그대로 굳어 기존 독자의 인물 재료가 통째로 빈다. 마이그레이션 백필 대신 전환 순간을 잡는다 —
        // 백필은 그 시점의 공개 스토리만 덮고, 이후 같은 구멍이 다시 생기면 또 놓친다.
        if (story.isPubliclyVisible() && request.visibility != null && request.visibility != story.visibility) {
            storyPublicSnapshotService.refresh(story)
        }

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

        // 표지 교체(KNK-1126). 회원 소유 스토리만이고 객체 키는 이 스토리의 업로드 prefix 아래여야 한다.
        // 지우기는 DELETE /stories/{storyId}/thumbnail이 담당한다(여기서 null은 미전송·유지).
        request.thumbnailObjectKey?.let { objectKey ->
            val ownerPublicId = requireImageUploader(story)
            story.thumbnailImageUrl =
                approvedImageUrls[objectKey] ?: storyImageAccess.resolveUploadedUrl(story, ownerPublicId, UploadedImageKind.COVER, objectKey)
            // 새 객체는 새 판정이다 — 이전 표지가 PENDING·REJECTED였다고 물려받으면 이미지를 바꿔도
            // 계속 가려진다. 자동 검수 도입 시 이 자리에서 판정 결과로 설정한다.
            story.thumbnailModerationStatus = ImageModerationStatus.APPROVED
        }

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

        // 인물 전체 교체(KNK-1391). 인물 이미지는 각 인물에 종속되므로 함께 동기화한다.
        request.characters?.let { inputs -> syncCharacters(story, inputs, approvedImageUrls) }

        // 시작 설정 전체 교체(KNK-515 복수화). 추천 입력·엔딩은 각 시작 설정에 종속되므로 함께 동기화한다.
        request.startSettings?.let { inputs -> syncStartSettings(story, inputs) }

        // 자식 교체까지 모두 끝난 뒤에 "마지막 공개 버전" 스냅샷을 갱신한다(KNK-1065). 공개 상태가 아니면 no-op이라
        // 비공개 개작은 스냅샷에 들어가지 않는다. 여기가 스토리 애그리거트를 바꾸는 유일한 수정 경로다.
        storyPublicSnapshotService.refresh(story)
        eventPublisher.publishEvent(StoryIndexRequestedEvent(story.id))

        return buildEditForm(story)
    }

    /**
     * 인물 컬렉션을 요청과 동기화한다(전체 교체, KNK-1391). 시작 설정 동기화와 같은 규칙이다: `id`가 기존과
     * 맞으면 in-place 갱신(개명), 없으면 신규 추가, 요청에서 빠진 기존 인물은 이미지와 함께 삭제한다.
     * 없는 id·타 스토리 id·요청 내 중복 id는 400이다(조용한 무시 금지).
     *
     * 삭제는 DB 행만 지우고 **S3 객체는 남긴다** — 지난 채팅의 `[[URL]]` 마커가 그 객체를 가리킨다(KNK-1126 결정).
     */
    private fun syncCharacters(story: Story, inputs: List<GeneralCharacterInput>, approvedImageUrls: Map<String, String>) {
        requireDistinctCharacterNames(inputs.map { it.name })
        val existing = storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)
        val existingByPublicId = existing.associateBy { it.publicId }

        // 같은 인물을 두 번 지목하면 뒤엣것만 남고 앞엣것이 조용히 유실된다(시작 설정과 같은 불변식).
        val requestedPublicIds = inputs.mapNotNull { it.id?.let(StoryImageAccess::parsePublicIdOrNull) }
        if (requestedPublicIds.size != requestedPublicIds.toSet().size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "인물 ID는 요청 내에서 중복될 수 없습니다.")
        }

        val resolved = inputs.map { input ->
            val match = input.id?.let { raw ->
                existingByPublicId[StoryImageAccess.parsePublicIdOrNull(raw)]
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이 스토리에 속하지 않는 인물 ID입니다.")
            }
            input to match
        }
        val keptIds = resolved.mapNotNull { it.second?.id }.toSet()

        // 요청에서 빠진 인물은 이미지까지 지운다. FK는 PostgreSQL에서 ON DELETE CASCADE지만 테스트 스키마가
        // 다를 수 있어 자식을 명시적으로 지운다(시작 설정 동기화와 같은 방식).
        existing.filter { it.id !in keptIds }.forEach { removed ->
            // 인물 행을 잠근 뒤 이미지를 읽는다. 잠그지 않으면 동시에 들어온 이미지 추가
            // 승인 적용이 우리 조회 뒤에 커밋돼
            // 삭제 목록에서 빠지고, 인물만 지워져 FK가 깨진다(PR #273 Codex P2).
            storyCharacterRepository.findByIdForUpdate(removed.id)
            storyCharacterImageRepository.deleteAll(
                storyCharacterImageRepository.findByCharacterIdOrderBySortOrderAscIdAsc(removed.id),
            )
            storyCharacterRepository.delete(removed)
        }
        storyCharacterRepository.flush()

        // 개명 전 이름을 먼저 붙잡는다. 아래 임시 이름 단계가 엔티티의 name을 덮어써서, 뒤에서 읽으면
        // 이미지 이름 접두를 옛 이름으로 맞출 수 없다.
        val previousNames = resolved.mapNotNull { (_, match) -> match?.let { it.id to it.name } }.toMap()

        // 이름 교환(A↔B)은 최종 이름이 서로 달라 위 검증을 통과하지만, 순차 갱신 중간 상태가
        // uq_story_characters_name과 충돌해 커밋이 깨진다(PR #273 Codex P2). 바뀌는 이름을 먼저 임시값으로
        // 비켜 두고 한 번 flush하면 중간 충돌이 사라진다. 임시값은 공개 식별자라 다른 이름과 겹치지 않는다.
        val renamed = resolved.mapNotNull { (input, match) -> match?.takeIf { it.name != input.name } }
        if (renamed.isNotEmpty()) {
            // 임시값은 **요청 시점 난수**다. 공개 식별자를 쓰면 사용자가 그 값을 인물 이름으로 미리 지어 둘 수
            // 있어(이름 검증은 형식을 따지지 않는다) 임시 단계가 유니크 제약에 걸린다(PR #273 Codex P2).
            renamed.forEach { it.name = temporaryName() }
            storyCharacterRepository.flush()
        }

        // **새 객체를 올리는 요청만** 회원 소유 스토리로 제한한다(PR #273 Codex P2). 편집 폼이 기존 이미지를
        // id로 되돌려 보내는 것은 업로드가 아니라 유지이고, 게스트 스토리(간편 제작 산출물에도 이미지가 있다)의
        // 폼 왕복을 막을 이유가 없다. 업로드 키 검증에 소유자 식별자가 필요해 그때 한 번만 읽는다.
        val hasNewUpload = resolved.any { (input, _) -> input.images.orEmpty().any { !it.objectKey.isNullOrBlank() } }
        val ownerPublicId = if (hasNewUpload) requireImageUploader(story) else null

        resolved.forEach { (input, match) ->
            val character = match ?: StoryCharacter(story = story, name = input.name)
            val previousName = match?.let { previousNames.getValue(it.id) } ?: input.name
            character.name = input.name
            val saved = storyCharacterRepository.save(character)
            syncCharacterImages(story, saved, previousName, input.images, ownerPublicId, approvedImageUrls)
        }
    }

    /**
     * 인물 하나의 이미지를 동기화한다. [inputs]가 null이면 **유지**이고(인물을 개명했으면 이름 접두만 따라간다),
     * 리스트면 전체 교체다: `id` 항목은 그 행을 유지하고, `objectKey` 항목은 새로 추가하며, 빠진 기존은 삭제한다.
     */
    private fun syncCharacterImages(
        story: Story,
        character: StoryCharacter,
        previousName: String,
        inputs: List<GeneralCharacterImageInput>?,
        ownerPublicId: UUID?,
        approvedImageUrls: Map<String, String>,
    ) {
        // 이미지 목록을 읽기 전에 인물 행을 잠근다. 승인 적용과 이미지 삭제가 같은
        // 락을 쓰므로 두 경로가 직렬화된다 — 잠그지 않으면 우리가 목록을 읽은 뒤 커밋된 새 이미지가
        // 전체 교체(빈 배열 포함)를 그대로 살아남는다(PR #273 Codex P2).
        storyCharacterRepository.findByIdForUpdate(character.id)
        val existing = storyCharacterImageRepository.findByCharacterIdOrderBySortOrderAscIdAsc(character.id)
        if (inputs == null) {
            // 생략 = 미전송 = 유지. 개명했다면 `{인물이름}_{접미}` 규칙이 깨지지 않게 접두만 갈아끼운다.
            existing.forEach {
                it.imageName = requireStorableImageName(renameImagePrefix(it.imageName, previousName, character.name))
            }
            return
        }

        val existingByPublicId = existing.associateBy { it.publicId }
        val requestedPublicIds = inputs.mapNotNull { it.id?.let(StoryImageAccess::parsePublicIdOrNull) }
        if (requestedPublicIds.size != requestedPublicIds.toSet().size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지 ID는 요청 내에서 중복될 수 없습니다.")
        }
        val resolved = inputs.map { item ->
            val match = item.id?.let { raw ->
                existingByPublicId[StoryImageAccess.parsePublicIdOrNull(raw)]
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이 인물에 속하지 않는 이미지 ID입니다.")
            }
            item to match
        }

        // 최종 이름을 먼저 확정해 검증한다. 유지 항목이 이름을 생략하면 현재 이름(개명 시 접두 갱신)을 쓴다.
        val finalNames = resolved.map { (item, match) ->
            val name = item.imageName?.takeIf { it.isNotBlank() }
                ?: renameImagePrefix(requireNotNull(match).imageName, previousName, character.name)
            requireStorableImageName(requireValidImageName(character.name, name))
        }
        requireDistinctCharacterImageNames(finalNames)

        val keptIds = resolved.mapNotNull { it.second?.id }.toSet()
        storyCharacterImageRepository.deleteAll(existing.filter { it.id !in keptIds })
        storyCharacterImageRepository.flush()

        // 인물 이름과 같은 이유로 이미지 이름 교환도 임시값을 한 단계 거친다((character_id, image_name) 유니크).
        val renamed = resolved.mapIndexed { index, (_, match) -> match to finalNames[index] }
            .mapNotNull { (match, finalName) -> match?.takeIf { it.imageName != finalName } }
        if (renamed.isNotEmpty()) {
            renamed.forEach { it.imageName = temporaryName() }
            storyCharacterImageRepository.flush()
        }

        resolved.forEachIndexed { index, (item, match) ->
            if (match != null) {
                match.imageName = finalNames[index]
                match.sortOrder = index
            } else {
                storyCharacterImageRepository.save(
                    StoryCharacterImage(
                        character = character,
                        imageName = finalNames[index],
                        imageUrl = approvedImageUrls[item.objectKey] ?: storyImageAccess.resolveUploadedUrl(
                            story,
                            ownerPublicId,
                            UploadedImageKind.CHARACTER,
                            requireNotNull(item.objectKey),
                        ),
                        sortOrder = index,
                    ),
                )
            }
        }
    }

    /**
     * 저장 가능한 이미지 이름인지 본다(PR #273 Codex P2). 인물 이름 100자 + `_` + 접미 20자면 121자가 돼
     * `story_character_images.image_name VARCHAR(120)`을 넘는데, 요청 DTO는 **보낸 이름**만 재므로 개명이
     * 자동으로 만든 이름은 걸러지지 않는다. insert에서 500이 나기 전에 400으로 돌려준다.
     */
    private fun requireStorableImageName(imageName: String): String {
        if (imageName.length > MAX_IMAGE_NAME_LENGTH) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "인물 이름이 길어 이미지 이름이 ${MAX_IMAGE_NAME_LENGTH}자를 넘습니다. 인물 이름이나 이미지 접미를 줄여 주세요.",
            )
        }
        return imageName
    }

    /** 이름 교환의 임시값. 사용자가 미리 지어 둘 수 없도록 요청 시점 난수를 쓴다. */
    private fun temporaryName(): String = "#tmp-${UUID.randomUUID()}"

    /** 인물 개명에 이미지 이름 접두를 맞춘다. 규칙을 벗어난 이름(옛 접두가 아님)은 손대지 않는다. */
    private fun renameImagePrefix(imageName: String, previousName: String, newName: String): String =
        if (previousName == newName || !imageName.startsWith("${previousName}_")) {
            imageName
        } else {
            "${newName}_${imageName.removePrefix("${previousName}_")}"
        }

    /**
     * 이미지를 올릴 수 있는 소유자의 공개 식별자. 게스트 스토리는 익명으로도 수정할 수 있어(소유권 게이트 통과)
     * 여기서 따로 막는다 — 소유자가 없으면 올린 이미지의 책임 주체가 없다(스펙 §4-3-8 "회원 소유 스토리만").
     */
    private fun requireImageUploader(story: Story): UUID {
        val ownerId = story.userId ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "로그인 후 내 스토리로 가져와야 이미지를 올릴 수 있습니다.",
        )
        return storyImageAccess.resolveUserPublicId(ownerId)
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

    private companion object {
        /** `story_character_images.image_name` 컬럼 길이(V76). DTO의 @Size와 같은 값이다. */
        const val MAX_IMAGE_NAME_LENGTH = 120
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
            // 이미지 업로드(KNK-1126). 표지는 2단 폴백(업로드·생성 URL → 프리셋 키)을 쓰되 **검수 게이트는
            // 적용하지 않는다** — 소유자 화면이라 상태와 함께 원본을 보여야 한다.
            thumbnailUrl = imageUrlResolver.thumbnailUrlFor(story.thumbnailImageUrl, story.thumbnailImageKey),
            thumbnailModerationStatus = story.thumbnailModerationStatus,
            characters = buildEditCharacters(story.id),
        )
    }

    /** 편집 화면의 인물·이미지 목록(KNK-1126). 이미지를 한 번에 읽어 인물 수만큼 쿼리가 늘지 않게 한다. */
    private fun buildEditCharacters(storyId: Long): List<StoryEditCharacterResponse> {
        val characters = storyCharacterRepository.findByStoryIdOrderByIdAsc(storyId)
        if (characters.isEmpty()) {
            return emptyList()
        }
        val imagesByCharacterId = storyCharacterImageRepository.findAllByStoryId(storyId)
            .groupBy { it.character.id }
        return characters.map { character ->
            StoryEditCharacterResponse(
                id = character.publicId.toString(),
                name = character.name,
                // 소유자 화면이라 검수 상태와 무관하게 전부 싣고 상태를 함께 준다.
                images = imagesByCharacterId[character.id].orEmpty().map { image ->
                    CharacterImageResponse(
                        id = image.publicId.toString(),
                        imageName = image.imageName,
                        imageUrl = image.imageUrl,
                        moderationStatus = image.moderationStatus,
                    )
                },
            )
        }
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
