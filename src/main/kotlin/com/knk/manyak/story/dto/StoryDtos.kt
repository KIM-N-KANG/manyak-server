package com.knk.manyak.story.dto

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
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

@Schema(description = "스토리 목록 정렬")
enum class StorySort {
    NEW,
    POPULAR,
}

@Schema(description = "스토리 타겟")
enum class StoryTarget {
    ALL,
    TEEN,
    ADULT,
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
    @field:ArraySchema(
        schema = Schema(description = "사전 정의 태그 ID", example = "101"),
        minItems = 1,
        maxItems = 20,
        arraySchema = Schema(description = "사용자가 선택한 사전 정의 태그 ID 목록"),
    )
    val selectedTagIds: List<Long>,
)

@Schema(description = "간편 제작 스토리라인 생성 응답")
data class GenerateSimpleStorylinesResponse(
    @field:Schema(description = "간편 제작 진행 ID", example = "1")
    val simpleCreationId: Long,

    @field:Schema(description = "저장된 선택 태그")
    val selectedTags: List<SimpleStoryTagResponse>,

    @field:Size(min = 3, max = 3)
    @field:Schema(description = "AI가 생성한 예시 스토리라인 3개")
    val storylines: List<SimpleStorylineResponse>,

    @field:ArraySchema(
        schema = Schema(implementation = SimpleStoryHelpQuestionResponse::class),
        minItems = 1,
        arraySchema = Schema(description = "일반 제작 필드 자동 생성을 돕는 추가 질문 목록"),
    )
    val helpQuestions: List<SimpleStoryHelpQuestionResponse>,
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
)

@Schema(description = "간편 제작 도움 질문")
data class SimpleStoryHelpQuestionResponse(
    @field:Schema(description = "질문 ID", example = "1")
    val id: Long,

    @field:Schema(description = "질문 내용", example = "주인공이 반드시 되찾고 싶은 것은 무엇인가요?")
    val question: String,
)

@Schema(description = "간편 제작 일반 모드 초안 생성 요청")
data class GenerateGeneralStoryDraftRequest(
    @field:Schema(description = "간편 제작 진행 ID", example = "1")
    val simpleCreationId: Long,

    @field:Schema(description = "사용자가 선택한 스토리라인 ID", example = "1")
    val selectedStorylineId: Long,

    @field:ArraySchema(
        schema = Schema(
            description = "추가 정보",
            example = "강진우의 목표는 회귀 전 막지 못했던 세계의 멸망을 막고 가족을 지키는 것임",
            maxLength = 100,
        ),
        maxItems = 3,
        arraySchema = Schema(description = "선택한 스토리라인을 보완하는 자유 추가 정보 목록"),
    )
    @field:Size(max = 3)
    val additionalInfos: List<@Size(max = 100) String> = emptyList(),
)

@Schema(description = "간편 제작 일반 모드 초안 생성 응답")
data class GenerateGeneralStoryDraftResponse(
    @field:Schema(description = "간편 제작 진행 ID", example = "1")
    val simpleCreationId: Long,

    @field:Schema(description = "선택된 스토리라인 ID", example = "1")
    val selectedStorylineId: Long,

    @field:Schema(description = "일반 모드 이야기 생성 입력 초안")
    val draft: CreateGeneralStoryRequest,
)

@Schema(description = "일반 모드 이야기 생성 요청")
data class CreateGeneralStoryRequest(
    @field:Size(max = 500)
    @field:Schema(description = "커버 이미지 URL", example = "https://example.com/covers/moon-contract.png")
    val coverImageUrl: String? = null,

    @field:NotBlank
    @field:Size(min = 2, max = 30)
    @field:Schema(description = "제목", example = "달빛 아래의 계약")
    val title: String,

    @field:NotBlank
    @field:Size(max = 50)
    @field:Schema(description = "한 줄 소개", example = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기")
    val shortDescription: String,

    @field:NotEmpty
    @field:ArraySchema(
        schema = Schema(implementation = StoryGenre::class),
        minItems = 1,
        maxItems = 5,
        arraySchema = Schema(description = "장르 목록"),
    )
    val genres: List<StoryGenre>,

    @field:Size(max = 6)
    @field:Schema(description = "해시태그", example = "[\"마법\", \"계약\", \"기억상실\"]")
    val hashtags: List<String> = emptyList(),

    @field:NotBlank
    @field:Size(max = 5000)
    @field:Schema(description = "프롬프트 템플릿", example = "너는 이 세계의 서술자다. 사용자 입력에 따라 장면과 인물 대사를 이어간다.")
    val promptTemplate: String,

    @field:NotBlank
    @field:Size(max = 10000)
    @field:Schema(description = "스토리 정보", example = "달이 두 개인 왕국에서 마법은 계약으로만 사용할 수 있습니다.")
    val storyInfo: String,

    @field:NotBlank
    @field:Size(max = 5000)
    @field:Schema(description = "인물 정보", example = "주인공은 기억을 잃은 마법사이며, 조력자는 정체를 숨긴 왕실 기록관입니다.")
    val characterInfo: String,

    @field:NotBlank
    @field:Size(max = 1000)
    @field:Schema(description = "출력 형식", example = "상황 묘사와 인물 대사를 구분하고, 마지막에는 사용자가 선택할 수 있는 다음 행동 후보를 제시합니다.")
    val outputFormat: String,

    @field:NotBlank
    @field:Size(max = 20)
    @field:Schema(description = "시작 상황 이름", example = "비 내리는 여관의 편지")
    val startSituationName: String,

    @field:NotBlank
    @field:Size(max = 10000)
    @field:Schema(description = "대화 프롤로그", example = "비가 내리는 밤, 당신은 여관 2층 방에서 봉인된 편지를 발견합니다.")
    val conversationPrologue: String,

    @field:Size(max = 3)
    @field:Schema(description = "추천 입력", example = "[\"편지를 열어본다\", \"창밖의 인기척을 확인한다\"]")
    val recommendedInputs: List<@Size(max = 200) String> = emptyList(),

    @field:Size(max = 1000)
    @field:Schema(description = "상세 소개", example = "계약 마법이 지배하는 왕국에서 잃어버린 기억과 사라진 가족의 비밀을 추적하는 인터랙티브 판타지입니다.")
    val detailedIntroduction: String? = null,

    @field:Schema(description = "타겟", example = "TEEN")
    val target: StoryTarget,

    @field:Schema(description = "스토리 공개 여부", example = "PUBLIC")
    val visibility: StoryVisibility = StoryVisibility.PUBLIC,

    @field:Schema(description = "등록 상태", example = "DRAFT")
    val status: StoryStatus = StoryStatus.DRAFT,
)

@Schema(description = "이야기 생성 응답")
data class StoryCreateResponse(
    @field:Schema(description = "스토리 ID", example = "1")
    val id: Long,

    @field:Schema(description = "생성 모드", example = "SIMPLE")
    val mode: String,

    @field:Schema(description = "제목", example = "달빛 아래의 계약")
    val title: String,

    @field:Schema(description = "등록 상태", example = "DRAFT")
    val status: StoryStatus,

    @field:Schema(description = "생성 시각", example = "2026-06-10T12:00:00Z")
    val createdAt: Instant,
)

@Schema(description = "스토리 목록 항목")
data class StorySummaryResponse(
    @field:Schema(description = "스토리 ID", example = "1")
    val id: Long,

    @field:Schema(description = "제목", example = "달빛 아래의 계약")
    val title: String,

    @field:Schema(description = "한 줄 소개", example = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기")
    val summary: String,

    @field:ArraySchema(
        schema = Schema(implementation = StoryGenre::class),
        arraySchema = Schema(description = "장르 목록"),
    )
    val genres: List<StoryGenre>,

    @field:Schema(description = "작성자 닉네임", example = "manyak_writer")
    val authorNickname: String,

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

    @field:ArraySchema(
        schema = Schema(implementation = StoryGenre::class),
        arraySchema = Schema(description = "장르 목록"),
    )
    val genres: List<StoryGenre>,

    @field:Schema(description = "해시태그", example = "[\"마법\", \"계약\", \"기억상실\"]")
    val hashtags: List<String>,

    @field:Schema(description = "작성자 정보")
    val author: StoryAuthorResponse,

    @field:Schema(description = "채팅 수", example = "128")
    val chatCount: Long,

    @field:Schema(description = "좋아요 수", example = "32")
    val likeCount: Long,

    @field:Schema(description = "시작 상황 이름", example = "비 내리는 여관의 편지")
    val startSituationName: String,

    @field:Schema(description = "대화 프롤로그", example = "비가 내리는 밤, 당신은 여관 2층 방에서 봉인된 편지를 발견합니다.")
    val conversationPrologue: String,

    @field:Schema(description = "추천 입력", example = "[\"편지를 열어본다\", \"창밖의 인기척을 확인한다\"]")
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
    @field:Schema(description = "작성자 ID", example = "1")
    val id: Long,

    @field:Schema(description = "작성자 닉네임", example = "manyak_writer")
    val nickname: String,

    @field:Schema(description = "작성자 프로필 이미지 URL", example = "https://example.com/profile.png")
    val profileImageUrl: String?,
)
