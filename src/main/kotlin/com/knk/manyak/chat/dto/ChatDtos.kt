package com.knk.manyak.chat.dto

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant

@Schema(description = "채팅 생성 요청")
data class CreateChatRequest(
    @field:Schema(description = "채팅을 시작할 스토리 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val storyId: String,

    // 시작 설정 복수화(KNK-515): 어느 시작 설정으로 시작할지 선택한다. 생략하면 스토리의 첫(기본) 시작 설정을 쓴다.
    // 지정한 값이 이 스토리에 속하지 않으면 404다(조용한 폴백 금지).
    @field:Schema(
        description = "채팅을 시작할 시작 설정 ID(공개 식별자). 생략하면 스토리의 첫 시작 설정을 사용한다.",
        example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        nullable = true,
    )
    val startSettingId: String? = null,
)

@Schema(description = "채팅 생성 응답")
data class CreateChatResponse(
    @field:Schema(
        description = "채팅 ID(추측 불가능한 공개 식별자). 클라이언트는 이 값을 로컬스토리지에 저장해 이전 채팅 목록 구성에 사용합니다.",
        example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
    )
    val id: String,

    @field:Schema(description = "스토리 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val storyId: String,

    @field:Schema(
        description = "채팅 시작 프롤로그",
        example = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다. 입학식 전 수행되는 적성 검사. 묘한 긴장감이 검사장을 감싼다.",
    )
    val prologue: String,

    @field:ArraySchema(
        schema = Schema(description = "추천 입력", example = "검사장을 둘러본다."),
        arraySchema = Schema(
            description = "시작 화면에 노출할 추천 입력 목록. 시작 설정이나 등록된 추천 입력이 없으면 빈 배열입니다.",
            example = """["검사장을 둘러본다.","마법수정에 손을 올린다.","주변 학생들에게 말을 건다."]""",
        ),
    )
    val suggestedInputs: List<String>,

    @field:Schema(description = "생성 시각", example = "2026-06-12T12:00:00Z")
    val createdAt: Instant,
)

@Schema(description = "채팅 ID 목록 조회 요청")
data class BatchChatRequest(
    @field:NotEmpty
    @field:Size(max = 100)
    @field:Schema(
        description = "클라이언트가 로컬스토리지에 보관 중인 채팅 ID(공개 식별자) 목록",
        example = """["3f2504e0-4f89-41d3-9a0c-0305e82c3301","9c5b94b1-35ad-49bb-b118-8e8fc24abf80"]""",
    )
    @field:ArraySchema(
        schema = Schema(description = "채팅 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"),
        minItems = 1,
        maxItems = 100,
        arraySchema = Schema(
            description = "클라이언트가 로컬스토리지에 보관 중인 채팅 ID(공개 식별자) 목록",
            example = """["3f2504e0-4f89-41d3-9a0c-0305e82c3301","9c5b94b1-35ad-49bb-b118-8e8fc24abf80"]""",
        ),
    )
    val chatIds: List<String>,
)

@Schema(description = "채팅 목록 항목")
data class ChatSummaryResponse(
    @field:Schema(description = "채팅 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val id: String,

    @field:Schema(description = "스토리 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val storyId: String,

    @field:Schema(description = "스토리 제목", example = "호아킨 아카데미의 무속성 신입생")
    val storyTitle: String,

    @field:Schema(description = "마지막으로 생성된 이야기 일부", example = "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.")
    val lastStoryPreview: String,

    @field:Schema(description = "이 채팅에서 사용자가 이어쓴 횟수(완료된 턴 수)", example = "2")
    val turnCount: Int,

    @field:ArraySchema(
        schema = Schema(description = "도달한 엔딩 이름", example = "왕좌를 되찾다"),
        arraySchema = Schema(description = "이 채팅에서 도달한 엔딩 이름(도달 전이면 빈 배열). 프론트가 스토리별로 합산합니다."),
    )
    val reachedEndings: List<String>,

    @field:Schema(description = "마지막 진행 시각", example = "2026-06-12T12:10:00Z")
    val updatedAt: Instant,
)

@Schema(description = "채팅 상세 응답")
data class ChatDetailResponse(
    @field:Schema(description = "채팅 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val id: String,

    @field:Schema(description = "스토리 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val storyId: String,

    @field:Schema(description = "스토리 제목", example = "호아킨 아카데미의 무속성 신입생")
    val storyTitle: String,

    @field:Schema(
        description = "채팅 시작 프롤로그",
        example = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다. 입학식 전 수행되는 적성 검사. 묘한 긴장감이 검사장을 감싼다.",
    )
    val prologue: String,

    @field:Schema(
        description = "채팅 진행 턴 목록",
        example = """[{"id":1,"userInput":"이름은 강진우고 무속성 판정을 받은 호아킨 아카데미 1학년이야.","aiOutput":"강진우라는 이름이 검사장 한쪽 기록판에 새겨졌다. 무속성이라는 판정은 조용한 웅성거림을 불러왔다.","createdAt":"2026-06-12T12:05:00Z"},{"id":2,"userInput":"마법수정에서 아무 빛도 나오지 않았지만, 내려가는 순간 수정이 금 가더니 깨져버렸다.","aiOutput":"검사장은 한순간 숨소리조차 사라진 듯 조용해졌다. 깨질 리 없는 수정의 파편이 단상 위에서 차갑게 빛났다.","createdAt":"2026-06-12T12:10:00Z"}]""",
    )
    @field:ArraySchema(
        schema = Schema(implementation = ChatTurnResponse::class),
        arraySchema = Schema(description = "채팅 진행 턴 목록"),
    )
    val turns: List<ChatTurnResponse>,

    @field:ArraySchema(
        schema = Schema(description = "추천 입력", example = "검사장을 둘러본다."),
        arraySchema = Schema(
            description = "아직 한 번도 이어쓰지 않아 turns가 비어 있을 때, 시작 화면에 노출할 기본 추천 입력 목록입니다. " +
                "진행 턴이 있으면(turns가 비어 있지 않으면) 다음 행동은 마지막 턴의 choices로 안내하므로 빈 배열입니다. " +
                "시작 설정이나 등록된 추천 입력이 없어도 빈 배열입니다.",
            example = """["검사장을 둘러본다.","마법수정에 손을 올린다.","주변 학생들에게 말을 건다."]""",
        ),
    )
    val suggestedInputs: List<String>,
)

@Schema(description = "채팅 진행 턴")
data class ChatTurnResponse(
    @field:Schema(description = "턴 ID", example = "1")
    val id: Long,

    @field:Schema(
        description = "사용자 입력. 캐릭터 프로필 설정, 다음 행동, 대사, 분위기, 감정, 연출 방향 등을 자연어로 입력할 수 있습니다.",
        example = "마법수정에서 아무 빛도 나오지 않았지만, 내려가는 순간 수정이 금 가더니 깨져버렸다.",
    )
    val userInput: String,

    @field:Schema(description = "사용자 입력을 바탕으로 AI가 이어쓴 이야기", example = "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.")
    val aiOutput: String,

    @field:ArraySchema(
        schema = Schema(description = "다음 행동 선택지", example = "주변을 살핀다."),
        arraySchema = Schema(description = "이 턴에서 AI가 제안한 다음 행동 선택지 목록"),
    )
    val choices: List<String>,

    @field:Schema(description = "생성 시각", example = "2026-06-12T12:10:00Z")
    val createdAt: Instant,
)

@Schema(description = "채팅 이어쓰기 요청")
data class ContinueChatRequest(
    @field:NotBlank
    @field:Size(max = 3000)
    @field:Schema(
        description = "사용자 입력. 첫 입력에서는 이름, 성향, 능력치, 배경 등 캐릭터 프로필을 설정할 수 있고, 이후에는 다음 사건, 행동, 대사, 분위기, 감정, 연출 방향 등을 입력합니다. 응답 완료 시 사용자 입력과 AI 출력이 하나의 채팅 턴으로 저장됩니다.",
        example = "다들 평범하게 속성을 발현한다. 나는 검사를 했지만 마법수정에서 아무런 빛이 나오지 않았다. 무속성 판정을 받고 단상 아래로 내려가는 중 마법수정에 금이 가더니 순식간에 깨져버렸다.",
    )
    val userInput: String,
)

@Schema(description = "AI 응답 재생성 요청")
data class RegenerateChatRequest(
    @field:Positive
    @field:Schema(
        description = "재생성할 마지막 턴 ID(공개 채팅 상세의 turns[].id). 서버가 보는 마지막 턴과 다르면 409로 거절합니다. " +
            "재생성은 이 마지막 턴의 AI 출력과 선택지만 같은 사용자 입력으로 다시 생성해 교체하며, 이전 출력·선택지는 버전 이력(V37)에 보존됩니다.",
        example = "3",
    )
    val turnId: Long,
)

@Schema(description = "SSE 스트리밍 시작 이벤트 예시")
data class ChatStreamStartedEvent(
    @field:Schema(description = "채팅 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val chatId: String,
)

@Schema(description = "SSE 토큰 이벤트 예시")
data class ChatStreamTokenEvent(
    @field:Schema(description = "AI가 생성 중인 글자 또는 토큰", example = "검")
    val text: String,
)

@Schema(description = "SSE 완료 이벤트 예시")
data class ChatStreamCompletedEvent(
    @field:Schema(description = "채팅 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val chatId: String,

    @field:Schema(description = "저장된 턴 ID", example = "3")
    val turnId: Long,

    @field:Schema(description = "이번 턴에서 최종 저장된 AI 출력 전체", example = "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.")
    val aiOutput: String,

    @field:ArraySchema(
        schema = Schema(description = "다음 행동 선택지", example = "주변을 살핀다."),
        arraySchema = Schema(description = "이번 턴에서 AI가 제안한 다음 행동 선택지 목록"),
    )
    val choices: List<String>,

    // 엔딩은 이름으로 식별한다(KNK-462). 순차 PK는 노출하지 않으므로 도달 엔딩도 이름으로 싣는다.
    @field:Schema(description = "이번 턴에 도달한 엔딩 이름(엔딩 응답이 아니면 null)", nullable = true, example = "왕좌를 되찾다")
    val reachedEnding: String? = null,
)

@Schema(description = "SSE 오류 이벤트 예시")
data class ChatStreamErrorEvent(
    @field:Schema(description = "오류 코드", example = "AI_STREAM_FAILED")
    val code: String,

    @field:Schema(description = "오류 메시지", example = "AI 응답 생성 중 오류가 발생했습니다.")
    val message: String,
)
