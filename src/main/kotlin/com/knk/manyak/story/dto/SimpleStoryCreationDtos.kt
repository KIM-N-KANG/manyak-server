package com.knk.manyak.story.dto

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
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
        example = """[{"tagId":101,"name":"게임","category":"GENRE"},{"tagId":205,"name":"소심한","category":"PROTAGONIST"},{"tagId":309,"name":"위험한","category":"SUPPORTING_CHARACTER"}]""",
    )
    @field:ArraySchema(
        schema = Schema(implementation = SimpleStoryTagResponse::class),
        arraySchema = Schema(
            description = "저장된 선택 태그",
            example = """[{"tagId":101,"name":"게임","category":"GENRE"},{"tagId":205,"name":"소심한","category":"PROTAGONIST"},{"tagId":309,"name":"위험한","category":"SUPPORTING_CHARACTER"}]""",
        ),
    )
    val selectedTags: List<SimpleStoryTagResponse>,

    @field:Size(min = 3, max = 3)
    @field:ArraySchema(
        schema = Schema(implementation = SimpleStorylineResponse::class),
        minItems = 3,
        maxItems = 3,
        arraySchema = Schema(
            description = "AI가 생성한 예시 스토리라인 3개. 각 스토리라인은 추가 입력을 돕는 도움 질문 3개를 포함합니다.",
            example = """[{"id":1,"story":"기억을 잃은 주인공이 금지된 숲에서 자신의 과거와 계약의 비밀을 추적합니다.","helpQuestions":[{"id":1,"question":"주인공이 반드시 되찾고 싶은 것은 무엇인가요?"},{"id":2,"question":"계약의 대가는 무엇이면 좋을까요?"},{"id":3,"question":"첫 장면은 어디에서 시작되면 좋을까요?"}]},{"id":2,"story":"비밀스러운 조력자가 남긴 편지를 따라 사라진 왕국의 진실에 다가갑니다.","helpQuestions":[{"id":4,"question":"편지를 남긴 인물은 누구인가요?"},{"id":5,"question":"왕국이 사라진 이유는 무엇인가요?"},{"id":6,"question":"주인공은 진실을 공개할까요, 숨길까요?"}]},{"id":3,"story":"닫혀 있던 별의 문이 열리며 주인공은 세계를 바꿀 선택 앞에 섭니다.","helpQuestions":[{"id":7,"question":"별의 문 너머에는 무엇이 있나요?"},{"id":8,"question":"주인공과 함께할 동료는 어떤 인물인가요?"},{"id":9,"question":"마지막 선택은 어떤 희생을 요구하나요?"}]}]""",
        ),
    )
    val storylines: List<SimpleStorylineResponse>,
)

@Schema(description = "간편 제작 태그 목록 항목")
data class SimpleStoryTagListItemResponse(
    @field:Schema(description = "태그 ID", example = "101")
    val tagId: Long,

    @field:Schema(description = "태그 이름", example = "판타지")
    val name: String,

    @field:Schema(description = "태그 분류", example = "GENRE")
    val category: SimpleStoryTagCategory,
)

@Schema(description = "간편 제작 저장 태그")
data class SimpleStoryTagResponse(
    @field:Schema(description = "태그 ID", example = "101")
    val tagId: Long,

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
    val story: String,

    @field:Size(min = 3, max = 3)
    @field:ArraySchema(
        schema = Schema(implementation = SimpleStoryHelpQuestionResponse::class),
        minItems = 3,
        maxItems = 3,
        arraySchema = Schema(
            description = "해당 스토리라인의 추가 입력을 돕는 도움 질문 3개",
            example = """[{"id":1,"question":"주인공이 반드시 되찾고 싶은 것은 무엇인가요?"},{"id":2,"question":"계약의 대가는 무엇이면 좋을까요?"},{"id":3,"question":"첫 장면은 어디에서 시작되면 좋을까요?"}]""",
        ),
    )
    val helpQuestions: List<SimpleStoryHelpQuestionResponse>,
)

@Schema(description = "간편 제작 도움 질문")
data class SimpleStoryHelpQuestionResponse(
    @field:Schema(description = "질문 ID", example = "1")
    val id: Long,

    @field:Schema(description = "질문 내용", example = "주인공이 반드시 되찾고 싶은 것은 무엇인가요?")
    val question: String,
)

@Schema(description = "간편 제작 이야기 생성 요청")
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
        maxItems = 3,
        arraySchema = Schema(
            description = "선택한 스토리라인을 보완하는 자유 추가 정보 목록",
            example = """["주인공의 목표는 회귀 전 막지 못했던 세계의 멸망을 막는 것임","결말은 주인공의 희생으로 세계가 구원되는 여운 있는 해피엔딩","정체를 숨긴 인물은 적이자 조력자가 될 수 있음"]""",
        ),
    )
    @field:Size(max = 3)
    val additionalInfos: List<@Size(max = 100) String> = emptyList(),
)

@Schema(description = "간편 제작 이야기 생성 응답")
data class SimpleStoryCreateResponse(
    @field:Schema(description = "생성된 스토리 ID. 클라이언트는 이 값을 로컬스토리지에 저장해 내 스토리 목록 구성에 사용합니다.", example = "10")
    val storyId: Long,

    @field:Schema(description = "스토리 제목", example = "잿빛 왕관")
    val title: String,

    @field:Schema(description = "한 줄 소개", example = "무너진 왕국에서, 견습 기사인 당신이 옥좌의 진실을 좇는다.")
    val oneLineIntro: String?,

    @field:Schema(description = "스토리 설명", example = "역병과 반란으로 무너진 아르덴 왕국. 선왕은 의문의 죽음을 맞았고...")
    val description: String?,

    @field:Schema(description = "장르. 사용자가 선택한 장르 태그명을 결합한 표시용 문자열입니다.", example = "다크 판타지, 정치극")
    val genre: String?,

    @field:Schema(description = "AI 프롬프트 구성용 스토리 설정")
    val settings: SimpleStorySettingsResponse,

    @field:Schema(description = "스토리 시작 설정")
    val startSetting: SimpleStoryStartSettingResponse,

    @field:ArraySchema(
        schema = Schema(description = "사용자 첫 입력 추천 문구", example = "레이에게 문을 열어준다"),
        arraySchema = Schema(
            description = "사용자 첫 입력 추천 문구 목록",
            example = """["레이에게 문을 열어준다","*검을 가까이 둔 채 경계하며* 누구냐고 묻는다","못 들은 척 침묵한다"]""",
        ),
    )
    val suggestedInputs: List<String>,
)

@Schema(description = "간편 제작 스토리 설정")
data class SimpleStorySettingsResponse(
    @field:Schema(description = "세계관/전제/갈등 등 스토리 정보")
    val worldSetting: String,

    @field:Schema(description = "등장인물 정보")
    val characterSetting: String,

    @field:Schema(description = "주인공(유저 역할) 정보")
    val userRoleSetting: String?,

    @field:Schema(description = "전개 규칙/문체 등 출력 형식")
    val ruleSetting: String?,
)

@Schema(description = "간편 제작 스토리 시작 설정")
data class SimpleStoryStartSettingResponse(
    @field:Schema(description = "시작 장면 이름", example = "선왕의 장례식 날")
    val name: String,

    @field:Schema(description = "도입부 내레이션", example = "잿빛 비가 사흘째 왕성을 적신다...")
    val prologue: String?,

    @field:Schema(description = "시작 상황", example = "장례식이 끝난 늦은 밤, 기사단 숙소...")
    val startSituation: String?,
)
