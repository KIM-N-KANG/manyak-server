package com.knk.manyak.story.dto

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
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
    val storyIds: List<@Min(1) Long>,
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
