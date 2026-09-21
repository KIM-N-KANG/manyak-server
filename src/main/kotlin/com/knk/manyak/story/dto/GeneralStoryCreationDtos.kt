package com.knk.manyak.story.dto

import com.knk.manyak.story.entity.StoryCharacterImage
import com.knk.manyak.story.entity.StoryVisibility
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** 추천 입력 개수(채팅 시작 화면 계약과 동일, 정확히 3개). */
const val GENERAL_SUGGESTED_INPUTS_SIZE = 3

/** 인물 수 상한. 간편 제작(주인공 1 + 주변 인물 5)과 같은 실질 상한을 쓴다. */
const val MAX_GENERAL_CHARACTERS = 6

/**
 * 일반 제작 스토리 등록 요청(단발, 스펙 §4-3-8). 검증 후 그대로 저장하며 AI를 호출하지 않는다
 * (컴파일은 희소 입력 확장인데 일반 제작 입력은 이미 확장된 형태라 크레딧 소모·게스트 한도 카운트가 없다).
 * 표지·인물 이미지는 등록 전에 presign(`POST /stories/images/presign`)으로 올린 객체 키를 함께 보낸다(KNK-1390).
 * 이미지 필드는 **회원만** 쓸 수 있다 — 소유자가 없으면 올린 이미지의 책임 주체가 없다.
 */
@Schema(description = "일반 제작 스토리 등록 요청(단발). 검증 후 그대로 저장하며 AI를 호출하지 않는다.")
data class CreateGeneralStoryRequest(
    @field:NotBlank(message = "제목은 비어 있을 수 없습니다.")
    @field:Size(max = 100, message = "제목은 100자를 넘을 수 없습니다.")
    @field:Schema(description = "제목", example = "달빛 아래의 계약")
    val title: String,

    @field:NotBlank(message = "한 줄 소개는 비어 있을 수 없습니다.")
    @field:Size(max = 255, message = "한 줄 소개는 255자를 넘을 수 없습니다.")
    @field:Schema(description = "한 줄 소개", example = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기")
    val oneLineIntro: String,

    @field:Schema(description = "주요 내용(선택).", nullable = true)
    val description: String? = null,

    // 장르는 stories.genre(VARCHAR(255))에 쉼표 결합 저장하므로, 개수·길이 상한으로 컬럼 초과를 막는다.
    // 최대 8개 × 30자 + 구분자 → 최대 254자 ≤ 255. 상한이 없으면 긴 입력이 검증(400)을 통과한 뒤 insert에서 500이 난다.
    @field:Size(min = 1, max = 8, message = "장르는 1개 이상 8개 이하여야 합니다.")
    @field:Schema(description = "장르 태그 목록(1~8개, 각 30자 이내)", example = "[\"판타지\",\"미스터리\"]")
    val genres: List<@NotBlank(message = "장르는 비어 있을 수 없습니다.") @Size(max = 30, message = "각 장르는 30자를 넘을 수 없습니다.") String>,

    @field:Valid
    @field:NotNull(message = "스토리 설정은 필수입니다.")
    @field:Schema(description = "스토리 설정 통글 4필드")
    val storySettings: GeneralStorySettingsInput,

    // 시작 설정 복수화(KNK-515): 추천 입력·엔딩은 각 시작 설정에 종속된다. 최소 1개 이상이어야 한다(빈 배열 400).
    @field:Valid
    @field:Size(min = 1, message = "시작 설정은 1개 이상이어야 합니다.")
    @field:Schema(description = "시작 설정 목록(최소 1개). 각 시작 설정에 추천 입력(정확히 3개)·엔딩(최대 10)이 종속된다.")
    val startSettings: List<@NotNull GeneralStartSettingInput>,

    @field:Valid
    @field:Size(max = MAX_MAIN_EVENTS, message = "주요 사건은 최대 ${MAX_MAIN_EVENTS}개까지 등록할 수 있습니다.")
    @field:Schema(description = "주요 사건 목록(최대 10, 선택). 스토리 스코프이며 배열 순서가 표시 순서가 된다.")
    val mainEvents: List<@NotNull MainEventItem> = emptyList(),

    @field:Schema(description = "공개 범위. 생략하면 PRIVATE.", example = "PRIVATE", defaultValue = "PRIVATE")
    val visibility: StoryVisibility = StoryVisibility.PRIVATE,

    // 표지 업로드(KNK-1390). presign으로 받은 draft 객체 키를 그대로 넣는다. 서버가 내 draft 경로 아래인지
    // 확인하고 HEAD로 존재·크기·형식을 재검증한 뒤 절대 URL을 굳힌다. 없으면 프리셋 자동 연결만 남는다.
    @field:Schema(
        description = "업로드한 표지의 객체 키(presign 응답의 objectKey). 회원만 쓸 수 있다.",
        nullable = true,
    )
    val thumbnailObjectKey: String? = null,

    @field:Valid
    @field:Size(max = MAX_GENERAL_CHARACTERS, message = "인물은 최대 ${MAX_GENERAL_CHARACTERS}명까지 등록할 수 있습니다.")
    @field:Schema(description = "인물 목록(최대 6명, 선택). 이름은 스토리 안에서 유일하다.")
    val characters: List<@NotNull GeneralCharacterInput> = emptyList(),
)

/**
 * 일반 제작 인물 입력(KNK-1390). 이름과 이미지만 받는다 — 외형 필드(성별·머리·의상)는 컴파일 산출물이라
 * 일반 제작에는 없고, 인물 묘사는 `storySettings.characterSetting` 통글이 담는다.
 */
@Schema(description = "일반 제작 인물 입력")
data class GeneralCharacterInput(
    @field:NotBlank(message = "인물 이름은 비어 있을 수 없습니다.")
    @field:Size(max = 100, message = "인물 이름은 100자를 넘을 수 없습니다.")
    @field:Schema(description = "인물 이름(스토리 내 유일)", example = "세린")
    val name: String,

    @field:Valid
    @field:Size(
        max = StoryCharacterImage.MAX_IMAGES_PER_CHARACTER,
        message = "인물당 이미지는 ${StoryCharacterImage.MAX_IMAGES_PER_CHARACTER}장까지 올릴 수 있습니다.",
    )
    @field:Schema(description = "이 인물의 이미지 목록(최대 10장, 선택). 배열 순서가 표시 순서가 된다.")
    val images: List<@NotNull GeneralCharacterImageInput> = emptyList(),
)

/** 인물 이미지 한 장의 입력(KNK-1390). 연결 규칙은 등록 후 추가(`POST .../characters/{id}/images`)와 같다. */
@Schema(description = "일반 제작 인물 이미지 입력")
data class GeneralCharacterImageInput(
    @field:NotBlank(message = "객체 키는 비어 있을 수 없습니다.")
    @field:Schema(description = "presign으로 받은 객체 키. 내 인물 업로드 prefix 아래여야 한다")
    val objectKey: String,

    @field:NotBlank(message = "이미지 이름은 비어 있을 수 없습니다.")
    @field:Size(max = 120, message = "이미지 이름은 120자를 넘을 수 없습니다.")
    @field:Schema(
        description = "`{인물이름}_{접미}` 형식. 접미는 1~20자 한글·영문·숫자이며 같은 인물 안에서 유일하다",
        example = "세린_웃음",
    )
    val imageName: String,
)

@Schema(description = "스토리 설정 통글 4필드(모두 필수)")
data class GeneralStorySettingsInput(
    @field:NotBlank(message = "세계관 설정은 비어 있을 수 없습니다.")
    @field:Schema(description = "세계관 설정")
    val worldSetting: String,

    @field:NotBlank(message = "등장인물 설정은 비어 있을 수 없습니다.")
    @field:Schema(description = "등장인물 설정")
    val characterSetting: String,

    @field:NotBlank(message = "주인공 설정은 비어 있을 수 없습니다.")
    @field:Schema(description = "주인공(사용자 역할) 설정")
    val userRoleSetting: String,

    @field:NotBlank(message = "규칙 설정은 비어 있을 수 없습니다.")
    @field:Schema(description = "규칙 설정")
    val ruleSetting: String,
)

@Schema(description = "시작 설정. 추천 입력·엔딩이 이 시작 설정에 종속된다(KNK-515 복수화).")
data class GeneralStartSettingInput(
    // 수정(PATCH)에서만 쓰는 매칭 키(공개 식별자 UUID). 기존 시작 설정을 지목해 in-place 갱신하고,
    // 없으면(null) 새 시작 설정으로 추가한다. 제작(POST)에서는 서버가 식별자를 생성하므로 무시된다.
    @field:Schema(
        description = "시작 설정 ID(공개 식별자). 수정 시 기존 시작 설정 매칭 키로만 사용하며, 제작 시에는 무시된다.",
        example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        nullable = true,
    )
    val id: String? = null,

    @field:NotBlank(message = "시작 장면 이름은 비어 있을 수 없습니다.")
    @field:Size(max = 100, message = "시작 장면 이름은 100자를 넘을 수 없습니다.")
    @field:Schema(description = "시작 장면 이름", example = "선왕의 장례식 날")
    val name: String,

    @field:NotBlank(message = "프롤로그는 비어 있을 수 없습니다.")
    @field:Schema(description = "도입부 내레이션(프롤로그)")
    val prologue: String,

    @field:NotBlank(message = "시작 상황은 비어 있을 수 없습니다.")
    @field:Schema(description = "시작 상황")
    val startSituation: String,

    @field:Size(
        min = GENERAL_SUGGESTED_INPUTS_SIZE,
        max = GENERAL_SUGGESTED_INPUTS_SIZE,
        message = "추천 입력은 정확히 ${GENERAL_SUGGESTED_INPUTS_SIZE}개여야 합니다.",
    )
    @field:Schema(description = "추천 입력(정확히 3개)")
    val suggestedInputs: List<@NotBlank(message = "추천 입력은 비어 있을 수 없습니다.") String>,

    @field:Valid
    @field:Size(max = MAX_ENDINGS, message = "엔딩은 시작 설정당 최대 ${MAX_ENDINGS}개까지 등록할 수 있습니다.")
    @field:Schema(description = "엔딩 목록(시작 설정당 최대 10, 선택). 배열 순서가 표시 순서가 된다.")
    val endings: List<@NotNull GeneralEndingItem> = emptyList(),
)

@Schema(description = "엔딩 입력 항목(유형 없이 이름으로 식별)")
data class GeneralEndingItem(
    @field:NotBlank(message = "엔딩 이름은 비어 있을 수 없습니다.")
    @field:Size(max = 100, message = "엔딩 이름은 100자를 넘을 수 없습니다.")
    @field:Schema(description = "엔딩 이름", example = "왕좌를 되찾다")
    val name: String,

    @field:Valid
    @field:NotNull(message = "엔딩 도달 조건은 필수입니다.")
    @field:Schema(description = "도달 조건(최소 턴 수 + 달성 조건)")
    val requirement: GeneralEndingRequirementInput,

    @field:NotBlank(message = "엔딩 에필로그는 비어 있을 수 없습니다.")
    @field:Schema(description = "도달 시 엔딩 응답 생성을 위한 출력 가이드")
    val epilogue: String,
)

@Schema(description = "엔딩 도달 조건(2파라미터). 최소 턴 수와 달성 조건을 모두 충족(AND)해야 도달한다.")
data class GeneralEndingRequirementInput(
    @field:Min(value = 0, message = "최소 턴 수는 0 이상이어야 합니다.")
    @field:Schema(description = "최소 턴 수(백엔드 결정적 판정)", example = "10")
    val minTurns: Int,

    @field:NotBlank(message = "달성 조건은 비어 있을 수 없습니다.")
    @field:Schema(description = "달성 조건(자연어, AI 정성 판정)", example = "주인공이 반란군을 규합해 왕좌를 되찾는다.")
    val achievementCondition: String,
)
