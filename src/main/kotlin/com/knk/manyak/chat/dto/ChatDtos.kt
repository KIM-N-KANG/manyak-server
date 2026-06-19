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
    @field:Positive
    @field:Schema(description = "채팅을 시작할 스토리 ID", example = "1")
    val storyId: Long,
)

@Schema(description = "채팅 생성 응답")
data class CreateChatResponse(
    @field:Schema(description = "채팅 ID. 클라이언트는 이 값을 로컬스토리지에 저장해 이전 채팅 목록 구성에 사용합니다.", example = "10")
    val id: Long,

    @field:Schema(description = "스토리 ID", example = "1")
    val storyId: Long,

    @field:Schema(
        description = "채팅 시작 프롤로그",
        example = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다. 입학식 전 수행되는 적성 검사. 묘한 긴장감이 검사장을 감싼다.",
    )
    val prologue: String,

    @field:Schema(description = "생성 시각", example = "2026-06-12T12:00:00Z")
    val createdAt: Instant,
)

@Schema(description = "채팅 ID 목록 조회 요청")
data class BatchChatRequest(
    @field:NotEmpty
    @field:Size(max = 100)
    @field:Schema(description = "클라이언트가 로컬스토리지에 보관 중인 채팅 ID 목록", example = "[10, 11, 12]")
    @field:ArraySchema(
        schema = Schema(description = "채팅 ID", example = "10"),
        minItems = 1,
        maxItems = 100,
        arraySchema = Schema(description = "클라이언트가 로컬스토리지에 보관 중인 채팅 ID 목록", example = "[10, 11, 12]"),
    )
    val chatIds: List<Long>,
)

@Schema(description = "채팅 목록 항목")
data class ChatSummaryResponse(
    @field:Schema(description = "채팅 ID", example = "10")
    val id: Long,

    @field:Schema(description = "스토리 ID", example = "1")
    val storyId: Long,

    @field:Schema(description = "스토리 제목", example = "호아킨 아카데미의 무속성 신입생")
    val storyTitle: String,

    @field:Schema(description = "마지막으로 생성된 이야기 일부", example = "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.")
    val lastStoryPreview: String,

    @field:Schema(description = "마지막 진행 시각", example = "2026-06-12T12:10:00Z")
    val updatedAt: Instant,
)

@Schema(description = "채팅 상세 응답")
data class ChatDetailResponse(
    @field:Schema(description = "채팅 ID", example = "10")
    val id: Long,

    @field:Schema(description = "스토리 ID", example = "1")
    val storyId: Long,

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

@Schema(description = "SSE 스트리밍 시작 이벤트 예시")
data class ChatStreamStartedEvent(
    @field:Schema(description = "채팅 ID", example = "10")
    val chatId: Long,
)

@Schema(description = "SSE 토큰 이벤트 예시")
data class ChatStreamTokenEvent(
    @field:Schema(description = "AI가 생성 중인 글자 또는 토큰", example = "검")
    val text: String,
)

@Schema(description = "SSE 완료 이벤트 예시")
data class ChatStreamCompletedEvent(
    @field:Schema(description = "채팅 ID", example = "10")
    val chatId: Long,

    @field:Schema(description = "저장된 턴 ID", example = "3")
    val turnId: Long,

    @field:Schema(description = "이번 턴에서 최종 저장된 AI 출력 전체", example = "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.")
    val aiOutput: String,
)

@Schema(description = "SSE 오류 이벤트 예시")
data class ChatStreamErrorEvent(
    @field:Schema(description = "오류 코드", example = "AI_STREAM_FAILED")
    val code: String,

    @field:Schema(description = "오류 메시지", example = "AI 응답 생성 중 오류가 발생했습니다.")
    val message: String,
)
