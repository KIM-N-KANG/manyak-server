package com.knk.manyak.story.dto

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * 스토리 수정 폼 응답(스펙 §4-3-8). 사용자 표시용 상세(GET /stories/{id})와 달리 통글 4필드까지 포함한 편집 가능 필드
 * 전체를 반환한다. 이미지·썸네일은 §4-3-9 범위라 제외한다. 레거시 엔딩(enabled=false)은 노출하지 않는다.
 */
@Schema(description = "스토리 수정 폼(편집 가능 필드 전체)")
data class StoryEditFormResponse(
    @field:Schema(description = "제목")
    val title: String,

    @field:Schema(description = "한 줄 소개", nullable = true)
    val oneLineIntro: String?,

    @field:Schema(description = "주요 내용", nullable = true)
    val description: String?,

    @field:Schema(description = "장르 태그 목록")
    val genres: List<String>,

    @field:Schema(description = "스토리 설정 통글 4필드")
    val storySettings: StoryEditSettingsResponse,

    @field:ArraySchema(
        schema = Schema(implementation = StoryStartSettingResponse::class),
        arraySchema = Schema(
            description = "시작 설정 목록(등록 순서, KNK-515 복수화). 각 시작 설정에 추천 입력·엔딩이 종속된다. 없으면 빈 배열.",
        ),
    )
    val startSettings: List<StoryStartSettingResponse>,

    @field:ArraySchema(schema = Schema(implementation = StoryMainEventResponse::class))
    val mainEvents: List<StoryMainEventResponse>,
)

@Schema(description = "스토리 설정 통글 4필드(아직 비어 있으면 null)")
data class StoryEditSettingsResponse(
    val worldSetting: String?,
    val characterSetting: String?,
    val userRoleSetting: String?,
    val ruleSetting: String?,
)

/**
 * 스토리 부분 갱신 요청(PATCH, 스펙 §4-3-8). 모든 필드가 선택이며, **보낸 필드만 교체하고 나머지는 유지**한다
 * (null = 미전송·유지). 리스트(suggestedInputs·mainEvents·endings)는 보내면 전체 교체, 빈 배열이면 전부 삭제다.
 * 수정 가능 필드는 일반 제작 요청과 동일하며, 간편 제작으로 만든 스토리도 같은 계약으로 수정한다.
 */
@Schema(description = "스토리 부분 갱신 요청. 보낸 필드만 교체하고 나머지는 유지한다.")
data class UpdateStoryRequest(
    @field:Size(max = 100, message = "제목은 100자를 넘을 수 없습니다.")
    val title: String? = null,

    @field:Size(max = 255, message = "한 줄 소개는 255자를 넘을 수 없습니다.")
    val oneLineIntro: String? = null,

    val description: String? = null,

    // 보내면 최소 1개. stories.genre(VARCHAR(255)) 결합 저장이라 개수·길이 상한을 둔다(일반 제작과 동일).
    @field:Size(min = 1, max = 8, message = "장르는 1개 이상 8개 이하여야 합니다.")
    val genres: List<@NotBlank(message = "장르는 비어 있을 수 없습니다.") @Size(max = 30, message = "각 장르는 30자를 넘을 수 없습니다.") String>? = null,

    @field:Valid
    val storySettings: GeneralStorySettingsInput? = null,

    // 시작 설정 복수화(KNK-515): 보내면 전체 컬렉션을 교체(동기화)한다. 각 원소의 id(공개 식별자)가 기존과
    // 일치하면 in-place 갱신(채팅이 참조하는 시작 설정 행 identity 보존), 없으면 신규 추가, 요청에서 빠진 기존은 삭제한다.
    // 추천 입력·엔딩은 각 시작 설정에 종속되며(정확히 3개·최대 10개), 보내면 최소 1개 이상이어야 한다.
    @field:Valid
    @field:Size(min = 1, message = "시작 설정은 1개 이상이어야 합니다.")
    val startSettings: List<@NotNull GeneralStartSettingInput>? = null,

    @field:Valid
    @field:Size(max = MAX_MAIN_EVENTS, message = "주요 사건은 최대 ${MAX_MAIN_EVENTS}개까지 등록할 수 있습니다.")
    val mainEvents: List<@NotNull MainEventItem>? = null,
)
