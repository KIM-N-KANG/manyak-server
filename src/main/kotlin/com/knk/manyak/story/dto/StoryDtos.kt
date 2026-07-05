package com.knk.manyak.story.dto

import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant

@Schema(description = "스토리 ID 목록 조회 요청")
data class BatchStoryRequest(
    @field:NotEmpty
    @field:Size(max = 100)
    @field:Schema(
        description = "클라이언트가 로컬스토리지에 보관 중인 스토리 ID(공개 식별자) 목록",
        example = """["3f2504e0-4f89-41d3-9a0c-0305e82c3301","9c5b94b1-35ad-49bb-b118-8e8fc24abf80"]""",
    )
    @field:ArraySchema(
        schema = Schema(description = "스토리 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"),
        minItems = 1,
        maxItems = 100,
        arraySchema = Schema(
            description = "클라이언트가 로컬스토리지에 보관 중인 스토리 ID(공개 식별자) 목록",
            example = """["3f2504e0-4f89-41d3-9a0c-0305e82c3301","9c5b94b1-35ad-49bb-b118-8e8fc24abf80"]""",
        ),
    )
    val storyIds: List<String>,
)

@Schema(description = "스토리 목록 항목")
data class StorySummaryResponse(
    @field:Schema(description = "스토리 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val id: String,

    @field:Schema(description = "제목", example = "달빛 아래의 계약")
    val title: String,

    @field:Schema(description = "한 줄 소개", example = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기")
    val oneLineIntro: String,

    @field:ArraySchema(
        schema = Schema(description = "장르명", example = "판타지"),
        arraySchema = Schema(description = "장르명 목록", example = """["판타지","미스터리"]"""),
    )
    val genres: List<String>,

    @field:Schema(description = "작성자 정보. 작성자가 없는 스토리는 비어 있을 수 있습니다.", nullable = true)
    val author: StoryAuthorResponse?,

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
    @field:Schema(description = "스토리 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val id: String,

    @field:Schema(description = "커버 이미지 URL", example = "https://example.com/covers/moon-contract.png")
    val coverImageUrl: String?,

    @field:Schema(description = "제목", example = "달빛 아래의 계약")
    val title: String,

    @field:Schema(description = "한 줄 소개", example = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기")
    val oneLineIntro: String,

    @field:Schema(description = "스토리 설명", example = "계약 마법이 지배하는 왕국에서 잃어버린 기억과 사라진 가족의 비밀을 추적하는 인터랙티브 판타지입니다.")
    val description: String?,

    @field:ArraySchema(
        schema = Schema(description = "장르명", example = "다크 판타지"),
        arraySchema = Schema(description = "장르명 목록. 제작 시 선택한 장르 태그명입니다.", example = """["다크 판타지","정치극"]"""),
    )
    val genres: List<String>,

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

    @field:Schema(description = "스토리 시작 설정. 시작 설정이 없는 스토리는 비어 있을 수 있습니다.", nullable = true)
    val startSetting: StoryStartSettingResponse?,

    @field:ArraySchema(
        schema = Schema(description = "추천 입력", example = "편지를 열어본다"),
        arraySchema = Schema(description = "추천 입력", example = """["편지를 열어본다","창밖의 인기척을 확인한다","여관 주인을 찾아간다"]"""),
    )
    val suggestedInputs: List<String>,

    @field:Schema(description = "스토리 공개 여부. 기본 생성 시 PRIVATE입니다.", example = "PRIVATE")
    val visibility: StoryVisibility,

    @field:Schema(description = "등록 상태", example = "PUBLISHED")
    val status: StoryStatus,

    @field:ArraySchema(
        schema = Schema(implementation = LorebookResponse::class),
        arraySchema = Schema(description = "스토리가 참조하는 로어북(장르 공용 용어 사전) 목록. 없으면 빈 배열입니다."),
    )
    val lorebooks: List<LorebookResponse>,

    @field:ArraySchema(
        schema = Schema(implementation = StoryEndingResponse::class),
        arraySchema = Schema(description = "스토리 엔딩 목록. 없으면 빈 배열입니다."),
    )
    val endings: List<StoryEndingResponse>,

    @field:ArraySchema(
        schema = Schema(implementation = StoryMainEventResponse::class),
        arraySchema = Schema(description = "스토리 주요 사건 목록(표시 순서). 없으면 빈 배열입니다."),
    )
    val mainEvents: List<StoryMainEventResponse>,

    @field:Schema(description = "생성 시각", example = "2026-06-10T12:00:00Z")
    val createdAt: Instant,
)

@Schema(description = "스토리가 참조하는 로어북(장르 공용 용어 사전)")
data class LorebookResponse(
    @field:Schema(description = "로어북 ID", example = "1")
    val id: Long,

    @field:Schema(description = "로어북 이름", example = "무협 용어집")
    val name: String,

    @field:Schema(description = "장르. 없을 수 있습니다.", example = "무협", nullable = true)
    val genre: String?,

    @field:Schema(description = "용어집 본문", example = "내공: 무공의 근원이 되는 기운")
    val content: String,
)

@Schema(description = "스토리 엔딩")
data class StoryEndingResponse(
    @field:Schema(description = "엔딩 제목", example = "왕좌를 되찾다")
    val title: String,

    @field:Schema(description = "엔딩 내용", example = "주인공은 잃어버린 왕좌를 되찾고 새 시대를 연다.")
    val content: String,

    @field:Schema(description = "도달 조건(자유 텍스트). 없을 수 있습니다.", example = "신뢰도 100 이상", nullable = true)
    val conditionText: String?,

    @field:Schema(description = "정렬 순서", example = "1")
    val sortOrder: Int,

    @field:Schema(description = "활성화 여부", example = "true")
    val enabled: Boolean,
)

@Schema(description = "로어북 카탈로그 목록 항목")
data class LorebookListItemResponse(
    @field:Schema(description = "로어북 ID", example = "1")
    val id: Long,

    @field:Schema(description = "로어북 이름", example = "무협 용어집")
    val name: String,

    @field:Schema(description = "장르. 없을 수 있습니다.", example = "무협", nullable = true)
    val genre: String?,
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

@Schema(description = "스토리 시작 설정")
data class StoryStartSettingResponse(
    @field:Schema(description = "시작 장면 이름", example = "선왕의 장례식 날")
    val name: String,

    @field:Schema(description = "도입부 내레이션", example = "잿빛 비가 사흘째 왕성을 적신다...")
    val prologue: String?,

    @field:Schema(description = "시작 상황", example = "장례식이 끝난 늦은 밤, 기사단 숙소...")
    val startSituation: String?,

    @field:Schema(description = "오프닝 장면. 아직 저작 전이면 null입니다.", nullable = true)
    val openingScene: String?,

    @field:Schema(description = "첫 AI 메시지. 아직 저작 전이면 null입니다.", nullable = true)
    val firstAiMessage: String?,
)
