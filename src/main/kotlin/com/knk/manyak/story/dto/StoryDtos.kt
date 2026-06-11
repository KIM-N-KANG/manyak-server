package com.knk.manyak.story.dto

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant

@Schema(description = "스토리 장르")
enum class StoryGenre {
    FANTASY,
    ROMANCE,
    THRILLER,
    MYSTERY,
    SF,
    DAILY,
    OTHER,
}

@Schema(description = "스토리 공개 상태")
enum class StoryVisibility {
    PUBLIC,
    PRIVATE,
}

@Schema(description = "스토리 등록 상태")
enum class StoryStatus {
    DRAFT,
    PUBLISHED,
}

@Schema(description = "간편 제작 태그 분류")
enum class SimpleStoryTagCategory {
    GENRE,
    PROTAGONIST,
    SUPPORTING_CHARACTER,
    MOOD,
    WORLDVIEW,
    OTHER,
}

@Schema(description = "간편 제작 스토리라인 생성 요청")
data class GenerateSimpleStorylinesRequest(
    @field:NotEmpty
    @field:Size(max = 20)
    @field:Schema(description = "사용자가 선택한 사전 정의 태그 ID 목록", example = "[101, 205, 309]")
    @field:ArraySchema(
        schema = Schema(description = "사전 정의 태그 ID", example = "101"),
        minItems = 1,
        maxItems = 20,
        arraySchema = Schema(description = "사용자가 선택한 사전 정의 태그 ID 목록", example = "[101, 205, 309]"),
    )
    val selectedTagIds: List<Long>,
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
            example = """[{"id":1,"title":"달빛 아래의 계약","summary":"기억을 잃은 주인공이 금지된 숲에서 자신의 과거를 추적합니다.","helpQuestions":[{"id":1,"question":"주인공이 반드시 되찾고 싶은 것은 무엇인가요?"},{"id":2,"question":"계약의 대가는 무엇이면 좋을까요?"},{"id":3,"question":"첫 장면은 어디에서 시작되면 좋을까요?"}]},{"id":2,"title":"왕국의 마지막 편지","summary":"비밀스러운 조력자가 남긴 편지를 따라 사라진 왕국의 진실에 다가갑니다.","helpQuestions":[{"id":4,"question":"편지를 남긴 인물은 누구인가요?"},{"id":5,"question":"왕국이 사라진 이유는 무엇인가요?"},{"id":6,"question":"주인공은 진실을 공개할까요, 숨길까요?"}]},{"id":3,"title":"깨어난 별의 문","summary":"닫혀 있던 별의 문이 열리며 주인공은 세계를 바꿀 선택 앞에 섭니다.","helpQuestions":[{"id":7,"question":"별의 문 너머에는 무엇이 있나요?"},{"id":8,"question":"주인공과 함께할 동료는 어떤 인물인가요?"},{"id":9,"question":"마지막 선택은 어떤 희생을 요구하나요?"}]}]""",
        ),
    )
    val storylines: List<SimpleStorylineResponse>,
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

    @field:Schema(description = "스토리라인 제목", example = "달빛 아래의 계약")
    val title: String,

    @field:Schema(description = "스토리라인 요약", example = "기억을 잃은 주인공이 금지된 숲에서 자신의 과거와 계약의 비밀을 추적합니다.")
    val summary: String,

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
    @field:Schema(description = "간편 제작 진행 ID", example = "1")
    val simpleCreationId: Long,

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
)

@Schema(description = "스토리 ID 목록 조회 요청")
data class BatchStoryRequest(
    @field:NotEmpty
    @field:Size(max = 100)
    @field:Schema(description = "클라이언트가 로컬스토리지에 보관 중인 스토리 ID 목록", example = "[1, 2, 3]")
    @field:ArraySchema(
        schema = Schema(description = "스토리 ID", example = "1"),
        minItems = 1,
        maxItems = 100,
        arraySchema = Schema(
            description = "클라이언트가 로컬스토리지에 보관 중인 스토리 ID 목록",
            example = "[1, 2, 3]",
        ),
    )
    val storyIds: List<Long>,
)

@Schema(description = "스토리 목록 항목")
data class StorySummaryResponse(
    @field:Schema(description = "스토리 ID", example = "1")
    val id: Long,

    @field:Schema(description = "제목", example = "달빛 아래의 계약")
    val title: String,

    @field:Schema(description = "한 줄 소개", example = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기")
    val summary: String,

    @field:Schema(description = "장르 목록", example = """["FANTASY","MYSTERY"]""")
    @field:ArraySchema(
        schema = Schema(implementation = StoryGenre::class),
        arraySchema = Schema(description = "장르 목록", example = """["FANTASY","MYSTERY"]"""),
    )
    val genres: List<StoryGenre>,

    @field:Schema(description = "작성자 닉네임. 작성자가 없는 스토리는 비어 있을 수 있습니다.", example = "manyak_writer", nullable = true)
    val authorNickname: String?,

    @field:Schema(description = "채팅 수", example = "128")
    val chatCount: Long,

    @field:Schema(description = "좋아요 수", example = "32")
    val likeCount: Long,

    @field:Schema(description = "등록 상태", example = "PUBLISHED")
    val status: StoryStatus,

    @field:Schema(description = "생성 시각", example = "2026-06-10T12:00:00Z")
    val createdAt: Instant,
)

@Schema(description = "스토리 상세 응답")
data class StoryDetailResponse(
    @field:Schema(description = "스토리 ID", example = "1")
    val id: Long,

    @field:Schema(description = "커버 이미지 URL", example = "https://example.com/covers/moon-contract.png")
    val coverImageUrl: String?,

    @field:Schema(description = "제목", example = "달빛 아래의 계약")
    val title: String,

    @field:Schema(description = "한 줄 소개", example = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기")
    val shortDescription: String,

    @field:Schema(description = "상세 소개", example = "계약 마법이 지배하는 왕국에서 잃어버린 기억과 사라진 가족의 비밀을 추적하는 인터랙티브 판타지입니다.")
    val detailedIntroduction: String?,

    @field:Schema(description = "장르 목록", example = """["FANTASY","MYSTERY"]""")
    @field:ArraySchema(
        schema = Schema(implementation = StoryGenre::class),
        arraySchema = Schema(description = "장르 목록", example = """["FANTASY","MYSTERY"]"""),
    )
    val genres: List<StoryGenre>,

    @field:ArraySchema(
        schema = Schema(description = "해시태그", example = "마법"),
        arraySchema = Schema(description = "해시태그", example = """["마법","계약","기억상실"]"""),
    )
    val hashtags: List<String>,

    @field:Schema(description = "작성자 정보. 작성자가 없는 스토리는 비어 있을 수 있습니다.", nullable = true)
    val author: StoryAuthorResponse?,

    @field:Schema(description = "채팅 수", example = "128")
    val chatCount: Long,

    @field:Schema(description = "좋아요 수", example = "32")
    val likeCount: Long,

    @field:Schema(description = "시작 상황 이름", example = "비 내리는 여관의 편지")
    val startSituationName: String,

    @field:Schema(description = "대화 프롤로그", example = "비가 내리는 밤, 당신은 여관 2층 방에서 봉인된 편지를 발견합니다.")
    val conversationPrologue: String,

    @field:ArraySchema(
        schema = Schema(description = "추천 입력", example = "편지를 열어본다"),
        arraySchema = Schema(description = "추천 입력", example = """["편지를 열어본다","창밖의 인기척을 확인한다","여관 주인을 찾아간다"]"""),
    )
    val recommendedInputs: List<String>,

    @field:Schema(description = "스토리 공개 여부", example = "PUBLIC")
    val visibility: StoryVisibility,

    @field:Schema(description = "등록 상태", example = "PUBLISHED")
    val status: StoryStatus,

    @field:Schema(description = "생성 시각", example = "2026-06-10T12:00:00Z")
    val createdAt: Instant,
)

@Schema(description = "스토리 작성자 정보")
data class StoryAuthorResponse(
    @field:Schema(description = "작성자 ID. 작성자가 없는 스토리는 비어 있을 수 있습니다.", example = "1", nullable = true)
    val id: Long?,

    @field:Schema(description = "작성자 닉네임", example = "manyak_writer")
    val nickname: String,

    @field:Schema(description = "작성자 프로필 이미지 URL", example = "https://example.com/profile.png")
    val profileImageUrl: String?,
)
