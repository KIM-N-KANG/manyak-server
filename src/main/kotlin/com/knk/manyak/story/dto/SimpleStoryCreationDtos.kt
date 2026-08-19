package com.knk.manyak.story.dto

import com.knk.manyak.story.entity.StoryCreationRequestStatus
import com.knk.manyak.story.entity.StoryCreationStage
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.text.Normalizer
import java.util.UUID

private val CHARACTER_NAME_WHITESPACE = Regex("(?U)\\s+")

@Schema(description = "간편 제작 태그 분류")
enum class SimpleStoryTagCategory {
    GENRE,
    PROTAGONIST,
    SUPPORTING_CHARACTER,
}

@Schema(description = "간편 제작 인물 성별")
enum class SimpleStoryCharacterGender {
    MALE,
    FEMALE,
}

@Schema(description = "간편 제작 스토리라인 생성 요청")
data class GenerateSimpleStorylinesRequest(
    @field:NotNull
    @field:Schema(
        description = "클라이언트 생성 요청 ID(UUID). 백그라운드 생성 복구 조회·재시도 멱등 키로 쓴다(스펙 §4-3-8).",
        example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val requestId: UUID,

    @field:Size(max = 20)
    @field:Schema(description = "사용자가 선택한 사전 정의 장르 태그 ID 목록", example = "[101, 102]")
    @field:ArraySchema(
        schema = Schema(description = "사전 정의 장르 태그 ID", example = "101"),
        maxItems = 20,
        arraySchema = Schema(description = "사용자가 선택한 사전 정의 장르 태그 ID 목록", example = "[101, 102]"),
    )
    val genreTagIds: List<@Min(1) Long> = emptyList(),

    // KNK-859: 인물 단위 계약 교체(KNK-845)에서 함께 빠졌던 장르 직접 입력을 되살린다. 인물 특징의
    // [SimpleStoryCharacterRequest.customTags]와 같은 규칙으로, 정규화 키가 같은 사전 정의 장르가 있으면 그 행에 연결한다.
    @field:Size(max = 20)
    @field:Schema(description = "사용자가 직접 입력한 장르 이름 목록", example = """["학원물"]""")
    @field:ArraySchema(
        schema = Schema(description = "직접 입력한 장르 이름", example = "학원물", maxLength = 30),
        maxItems = 20,
        arraySchema = Schema(description = "사용자가 직접 입력한 장르 이름 목록", example = """["학원물"]"""),
    )
    val customGenreTags: List<@NotBlank @Size(max = 30) String> = emptyList(),

    @field:Valid
    @field:Schema(description = "주인공 입력", requiredMode = Schema.RequiredMode.REQUIRED)
    val protagonist: SimpleStoryCharacterRequest,

    @field:Valid
    @field:Size(max = 5, message = "주변 인물은 최대 5명까지 입력할 수 있습니다.")
    @field:ArraySchema(
        schema = Schema(implementation = SimpleStoryCharacterRequest::class),
        maxItems = 5,
        arraySchema = Schema(description = "주변 인물 입력 목록(최대 5명)"),
    )
    val supportingCharacters: List<SimpleStoryCharacterRequest> = emptyList(),

    // AI trace 연결용 필드(KNK-751). 서버가 값을 만들지 않고 프론트가 보낸 것만 쓰며, 없으면 헤더를 생략한다.
    // [isRegenerated]는 서버가 판단할 수 없어 그대로 전달하지만, [parentCreationId]는 KNK-755부터 검증을 거친다.
    @field:Schema(
        description = "이 스토리라인 생성이 재생성인지 여부. 서버는 판단하지 않고 AI 호출에 그대로 전달합니다.",
        example = "true",
        nullable = true,
    )
    val isRegenerated: Boolean? = null,

    @field:Schema(
        description = "재생성이면 직전 생성의 creation_id(=그 요청의 requestId). 서버가 이 값을 검증해 " +
            "**통과한 경우에만** AI 호출 헤더로 전달합니다(자기참조 아님·해당 요청 존재·소유 연속성). " +
            "검증에 실패해도 이 요청은 정상 처리되며(400이 아닙니다) 체인 헤더만 생략됩니다. " +
            "실패 사유는 서버에 기록되므로, 재생성 체인이 이어지지 않았다면 이 값이 유효했는지 확인하세요.",
        example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        nullable = true,
    )
    val parentCreationId: UUID? = null,
) {
    // 장르 총량 상한(KNK-859). 필드별 @Size(max = 20)만으로는 선택 20 + 직접 입력 20 = 40까지 열려, 옛 계약의
    // 실질 상한(장르 합산 20)보다 늘어난다. 인물 특징의 [SimpleStoryCharacterRequest.hasAtMostThreeFeatures]와 같이
    // 중복 제거 전 요청 항목 수로 센다.
    @AssertTrue(message = "장르는 선택과 직접 입력을 합쳐 최대 20개까지 입력할 수 있습니다.")
    @Schema(hidden = true)
    fun hasAtMostTwentyGenres(): Boolean = genreTagIds.size + customGenreTags.size <= 20

    /**
     * [customGenreTags] 원소 길이 검증(KNK-859). 원소에 붙인 `@NotBlank`·`@Size`는 코틀린이 JVM 타입 애노테이션을
     * 기본으로 내보내지 않아 실제로 발동하지 않으므로(레포 전반의 기존 간극), 이 필드는 메서드 제약으로 직접 막는다.
     * 상한 30은 `story_creation_tags.name` 컬럼 길이이고, 서버가 trim 후 저장하므로 trim 기준으로 잰다.
     */
    @AssertTrue(message = "직접 입력 장르는 공백을 제외하고 1자 이상 30자 이하여야 합니다.")
    @Schema(hidden = true)
    fun hasValidCustomGenreTags(): Boolean = customGenreTags.all { it.trim().length in 1..30 }

    @AssertTrue(message = "인물 이름은 중복될 수 없습니다.")
    @Schema(hidden = true)
    fun hasUniqueCharacterNames(): Boolean {
        val keys = (listOf(protagonist) + supportingCharacters).mapNotNull { it.duplicateNameKey() }
        return keys.size == keys.distinct().size
    }
}

@Schema(description = "간편 제작 인물 입력")
data class SimpleStoryCharacterRequest(
    @field:Size(max = 30)
    @field:Schema(description = "인물 이름. 비우면 AI가 생성합니다.", example = "아린", maxLength = 30, nullable = true)
    val name: String? = null,

    @field:Schema(description = "인물 성별. 비우면 AI가 생성합니다.", example = "FEMALE", nullable = true)
    val gender: SimpleStoryCharacterGender? = null,

    @field:Schema(description = "선택한 사전 정의 특징 태그 ID 목록", example = "[205, 206]")
    val featureTagIds: List<@Min(1) Long> = emptyList(),

    @field:Schema(description = "직접 추가한 특징 태그 이름 목록", example = "[\"용감한\"]")
    val customTags: List<@NotBlank @Size(max = 30) String> = emptyList(),
) {
    @AssertTrue(message = "인물당 특징은 최대 3개까지 입력할 수 있습니다.")
    @Schema(hidden = true)
    fun hasAtMostThreeFeatures(): Boolean = featureTagIds.size + customTags.size <= 3

    fun cleanedName(): String? = name
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        ?.trim()
        ?.replace(CHARACTER_NAME_WHITESPACE, " ")
        ?.takeIf { it.isNotEmpty() }

    fun duplicateNameKey(): String? = cleanedName()
        ?.replace(CHARACTER_NAME_WHITESPACE, "")
        ?.lowercase()
}

@Schema(description = "간편 제작 스토리라인 생성 응답")
data class GenerateSimpleStorylinesResponse(
    @field:Schema(description = "간편 제작 진행 ID", example = "1")
    val simpleCreationId: Long,

    @field:Schema(description = "장르와 인물별로 정리한 저장 입력")
    val selectedTags: SimpleStorySelectedTagsResponse,

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

@Schema(description = "간편 제작 장르와 인물별 저장 입력")
data class SimpleStorySelectedTagsResponse(
    val genreTags: List<SimpleStoryTagResponse>,
    val protagonist: SimpleStorySelectedCharacterResponse,
    val supportingCharacters: List<SimpleStorySelectedCharacterResponse>,
)

@Schema(description = "간편 제작 인물별 저장 입력")
data class SimpleStorySelectedCharacterResponse(
    val name: String?,
    val gender: SimpleStoryCharacterGender?,
    val features: List<SimpleStoryTagResponse>,
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
    @field:NotNull
    @field:Schema(
        description = "클라이언트 생성 요청 ID(UUID). 백그라운드 생성 복구 조회·재시도 멱등 키로 쓴다(스펙 §4-3-8).",
        example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val requestId: UUID,

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

    // AI trace 연결용 통과 필드(KNK-751). 서버는 판단하지 않고 전달만 하며, 없으면 헤더를 생략한다.
    @field:Schema(
        description = "이 완성 요청이 재생성인지 여부. 서버는 판단하지 않고 AI 호출에 그대로 전달합니다.",
        example = "false",
        nullable = true,
    )
    val isRegenerated: Boolean? = null,
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

@Schema(description = "백그라운드 생성 요청 복구 조회 응답(스펙 §4-3-8)")
data class StoryCreationRequestStatusResponse(
    @field:Schema(description = "생성 단계", example = "STORY_COMPLETION")
    val stage: StoryCreationStage,

    @field:Schema(description = "진행 상태", example = "COMPLETED")
    val status: StoryCreationRequestStatus,

    @field:Schema(
        description = "COMPLETED일 때 원 POST 응답 본문과 동일 스키마, 그 외 null.",
        nullable = true,
    )
    val result: JsonNode?,
)
