package com.knk.manyak.story.dto

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Schema(description = "간편 제작 태그 분류")
enum class SimpleStoryTagCategory {
    GENRE,
    PROTAGONIST,
    SUPPORTING_CHARACTER,
}

@Schema(description = "간편 제작 스토리라인 생성 요청")
data class GenerateSimpleStorylinesRequest(
    @field:Size(max = 20)
    @field:Schema(description = "사용자가 선택한 사전 정의 태그 ID 목록", example = "[101, 205, 309]")
    @field:ArraySchema(
        schema = Schema(description = "사전 정의 태그 ID", example = "101"),
        maxItems = 20,
        arraySchema = Schema(description = "사용자가 선택한 사전 정의 태그 ID 목록", example = "[101, 205, 309]"),
    )
    val selectedTagIds: List<@Min(1) Long> = emptyList(),

    @field:Valid
    @field:Size(max = 20)
    @field:Schema(
        description = "사용자가 직접 추가한 태그 목록. 서버는 저장 후 선택 태그와 함께 AI 서버 요청에 사용합니다.",
        example = """[{"name":"비밀스러운 조력자","category":"SUPPORTING_CHARACTER"},{"name":"마법 학교","category":"GENRE"}]""",
    )
    @field:ArraySchema(
        schema = Schema(implementation = SimpleStoryCustomTagRequest::class),
        maxItems = 20,
        arraySchema = Schema(
            description = "사용자가 직접 추가한 태그 목록",
            example = """[{"name":"비밀스러운 조력자","category":"SUPPORTING_CHARACTER"},{"name":"마법 학교","category":"GENRE"}]""",
        ),
    )
    val customTags: List<SimpleStoryCustomTagRequest> = emptyList(),
) {
    @AssertTrue(message = "selectedTagIds 또는 customTags 중 하나 이상은 필요합니다.")
    @Schema(hidden = true)
    fun hasAnyTags(): Boolean = selectedTagIds.isNotEmpty() || customTags.isNotEmpty()
}

@Schema(description = "간편 제작 직접 추가 태그")
data class SimpleStoryCustomTagRequest(
    @field:NotBlank
    @field:Size(max = 30)
    @field:Schema(description = "사용자가 직접 입력한 태그 이름", example = "비밀스러운 조력자", maxLength = 30)
    val name: String,

    @field:Schema(description = "직접 추가 태그 분류", example = "SUPPORTING_CHARACTER")
    val category: SimpleStoryTagCategory,
)

@Schema(description = "간편 제작 스토리라인 생성 응답")
data class GenerateSimpleStorylinesResponse(
    @field:Schema(description = "간편 제작 진행 ID", example = "1")
    val simpleCreationId: Long,

    @field:Schema(
        description = "저장된 선택 태그",
        example = """[{"id":101,"name":"게임","category":"GENRE"},{"id":205,"name":"소심한","category":"PROTAGONIST"},{"id":309,"name":"위험한","category":"SUPPORTING_CHARACTER"}]""",
    )
    @field:ArraySchema(
        schema = Schema(implementation = SimpleStoryTagResponse::class),
        arraySchema = Schema(
            description = "저장된 선택 태그",
            example = """[{"id":101,"name":"게임","category":"GENRE"},{"id":205,"name":"소심한","category":"PROTAGONIST"},{"id":309,"name":"위험한","category":"SUPPORTING_CHARACTER"}]""",
        ),
    )
    val selectedTags: List<SimpleStoryTagResponse>,

    @field:Size(min = 3, max = 3)
    @field:ArraySchema(
        schema = Schema(implementation = SimpleStorylineResponse::class),
        minItems = 3,
        maxItems = 3,
        arraySchema = Schema(
            description = "AI가 생성한 예시 스토리라인 3개. 각 스토리라인은 추가 입력을 돕는 추천 추가 정보 3개를 포함합니다.",
            example = """[{"id":1,"storyline":"기억을 잃은 주인공이 금지된 숲에서 자신의 과거와 계약의 비밀을 추적합니다.","recommendedInfos":[{"id":1,"text":"주인공은 반드시 되찾고 싶은 기억이 하나 있다."},{"id":2,"text":"계약의 대가로 수명을 내어주어야 한다."},{"id":3,"text":"첫 장면은 비 내리는 폐허에서 시작된다."}]},{"id":2,"storyline":"비밀스러운 조력자가 남긴 편지를 따라 사라진 왕국의 진실에 다가갑니다.","recommendedInfos":[{"id":4,"text":"편지를 남긴 인물은 주인공의 옛 스승이다."},{"id":5,"text":"왕국은 금기를 어긴 대가로 하루아침에 사라졌다."},{"id":6,"text":"주인공은 결국 진실을 세상에 공개한다."}]},{"id":3,"storyline":"닫혀 있던 별의 문이 열리며 주인공은 세계를 바꿀 선택 앞에 섭니다.","recommendedInfos":[{"id":7,"text":"별의 문 너머에는 또 다른 세계가 존재한다."},{"id":8,"text":"주인공과 함께할 동료는 냉소적인 마법사다."},{"id":9,"text":"마지막 선택은 동료 중 한 명의 희생을 요구한다."}]}]""",
        ),
    )
    val storylines: List<SimpleStorylineResponse>,
)

@Schema(description = "간편 제작 태그 목록 항목")
data class SimpleStoryTagListItemResponse(
    @field:Schema(description = "태그 ID", example = "101")
    val id: Long,

    @field:Schema(description = "태그 이름", example = "판타지")
    val name: String,

    @field:Schema(description = "태그 분류", example = "GENRE")
    val category: SimpleStoryTagCategory,
)

@Schema(description = "간편 제작 저장 태그")
data class SimpleStoryTagResponse(
    @field:Schema(description = "태그 ID", example = "101")
    val id: Long,

    @field:Schema(description = "태그 이름", example = "기억을 잃은 주인공")
    val name: String,

    @field:Schema(description = "태그 분류", example = "PROTAGONIST")
    val category: SimpleStoryTagCategory,
)

@Schema(description = "간편 제작 예시 스토리라인")
data class SimpleStorylineResponse(
    @field:Schema(description = "스토리라인 ID", example = "1")
    val id: Long,

    @field:Schema(description = "AI가 생성한 스토리라인 본문", example = "기억을 잃은 주인공이 금지된 숲에서 자신의 과거와 계약의 비밀을 추적합니다.")
    val storyline: String,

    @field:Size(min = 3, max = 3)
    @field:ArraySchema(
        schema = Schema(implementation = SimpleStoryRecommendedInfoResponse::class),
        minItems = 3,
        maxItems = 3,
        arraySchema = Schema(
            description = "해당 스토리라인의 추가 입력을 돕는 추천 추가 정보 3개",
            example = """[{"id":1,"text":"주인공은 반드시 되찾고 싶은 기억이 하나 있다."},{"id":2,"text":"계약의 대가로 수명을 내어주어야 한다."},{"id":3,"text":"첫 장면은 비 내리는 폐허에서 시작된다."}]""",
        ),
    )
    val recommendedInfos: List<SimpleStoryRecommendedInfoResponse>,
)

@Schema(description = "간편 제작 추천 추가 정보")
data class SimpleStoryRecommendedInfoResponse(
    @field:Schema(description = "추천 추가 정보 ID", example = "1")
    val id: Long,

    @field:Schema(description = "추천 추가 정보 내용", example = "주인공은 반드시 되찾고 싶은 기억이 하나 있다.")
    val text: String,
)

@Schema(description = "스토리라인 평가 값")
enum class StorylineRating {
    GOOD,
    BAD,
}

@Schema(description = "스토리라인 평가 요청")
data class StorylineRatingRequest(
    @field:NotNull
    @field:Schema(description = "평가 값", example = "GOOD", requiredMode = Schema.RequiredMode.REQUIRED)
    val rating: StorylineRating?,
)

@Schema(description = "스토리라인 평가 응답")
data class StorylineRatingResponse(
    @field:Schema(description = "평가한 스토리라인 ID", example = "1")
    val id: Long,

    @field:Schema(description = "현재 평가 값", example = "GOOD")
    val rating: StorylineRating,
)

@Schema(description = "간편 제작 스토리 생성 요청")
data class CreateSimpleStoryRequest(
    @field:Min(1)
    @field:Schema(description = "간편 제작 진행 ID", example = "1")
    val simpleCreationId: Long,

    @field:Min(1)
    @field:Schema(description = "사용자가 선택한 스토리라인 ID", example = "2")
    val storylineId: Long,

    @field:Schema(
        description = "선택한 스토리라인을 보완하는 자유 추가 정보 목록",
        example = """["주인공의 목표는 회귀 전 막지 못했던 세계의 멸망을 막는 것임","결말은 주인공의 희생으로 세계가 구원되는 여운 있는 해피엔딩","정체를 숨긴 인물은 적이자 조력자가 될 수 있음"]""",
    )
    @field:ArraySchema(
        schema = Schema(
            description = "추가 정보",
            example = "강진우의 목표는 회귀 전 막지 못했던 세계의 멸망을 막고 가족을 지키는 것임",
            maxLength = 100,
        ),
        maxItems = 13,
        arraySchema = Schema(
            description = "선택한 스토리라인을 보완하는 자유 추가 정보 목록",
            example = """["주인공의 목표는 회귀 전 막지 못했던 세계의 멸망을 막는 것임","결말은 주인공의 희생으로 세계가 구원되는 여운 있는 해피엔딩","정체를 숨긴 인물은 적이자 조력자가 될 수 있음"]""",
        ),
    )
    // 프론트 최악값(자유 텍스트 10 + 스토리라인당 추천 태그 3 = 13)을 단일 배열로 그대로 수용한다(스펙 간극 B5).
    @field:Size(max = 13)
    val additionalInfos: List<@Size(max = 100) String> = emptyList(),
)

@Schema(description = "간편 제작 스토리 생성 응답")
data class SimpleStoryCreateResponse(
    @field:Schema(description = "생성된 스토리 ID(공개 식별자). 클라이언트는 이 값을 로컬스토리지에 저장해 내 스토리 목록 구성에 사용합니다.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val id: String,

    @field:Schema(description = "스토리 제목", example = "잿빛 왕관")
    val title: String,

    @field:Schema(description = "한 줄 소개", example = "무너진 왕국에서, 견습 기사인 당신이 옥좌의 진실을 좇는다.")
    val oneLineIntro: String?,

    @field:Schema(description = "스토리 설명", example = "역병과 반란으로 무너진 아르덴 왕국. 선왕은 의문의 죽음을 맞았고...")
    val description: String?,

    @field:ArraySchema(
        schema = Schema(description = "장르명", example = "다크 판타지"),
        arraySchema = Schema(
            description = "장르명 목록. 사용자가 선택한 장르 태그명입니다.",
            example = """["다크 판타지","정치극"]""",
        ),
    )
    val genres: List<String>,

    @field:ArraySchema(
        schema = Schema(implementation = StoryStartSettingResponse::class),
        arraySchema = Schema(description = "스토리 시작 설정 목록(KNK-515 복수화). 간편 제작은 1개다."),
    )
    val startSettings: List<StoryStartSettingResponse>,
)
