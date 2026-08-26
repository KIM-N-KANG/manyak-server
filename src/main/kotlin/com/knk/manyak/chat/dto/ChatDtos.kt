package com.knk.manyak.chat.dto

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
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

    @field:Schema(
        description = "참조 스토리 썸네일의 축소 변형 URL(§4-3-9 반응형 변형). 소스가 없으면 null.",
        example = "https://cdn.manyak.app/thumbnails/thumb_0012_sm.png",
        nullable = true,
    )
    val thumbnailUrlSm: String?,

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

    // 엔딩은 이름으로 식별한다(KNK-462). 순차 PK는 노출하지 않으므로 도달 엔딩도 이름으로 싣는다. SSE completed와 같은 계약.
    @field:Schema(description = "이번 턴에 도달한 엔딩 이름(도달 아니면 null)", nullable = true, example = "왕좌를 되찾다")
    val reachedEnding: String? = null,

    @field:Schema(description = "생성 시각", example = "2026-06-12T12:10:00Z")
    val createdAt: Instant,
)

@Schema(description = "채팅 공유 발급 응답(스펙 §4-3-11)")
data class CreateChatShareResponse(
    @field:Schema(
        description = "공유 열람 토큰(공개 식별자). 채팅 ID와 무관한 별도 UUID이며, 이 값으로 GET /shares/{shareId}를 무인증 열람합니다.",
        example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
    )
    val shareId: String,

    @field:Schema(description = "공유에 포함된 턴 수(발급 시점의 진행 턴 수 = 커트라인)", example = "2")
    val turnCount: Int,

    @field:Schema(description = "공유 발급 시각(멱등 재발급이면 최초 발급 시각)", example = "2026-06-12T12:00:00Z")
    val createdAt: Instant,
)

@Schema(description = "공유된 채팅 열람 응답(스펙 §4-3-11)")
data class ChatShareResponse(
    @field:Schema(description = "공유 열람 토큰(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
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

    @field:ArraySchema(
        schema = Schema(implementation = ChatShareTurnResponse::class),
        arraySchema = Schema(description = "공유 커트라인 이하의 채팅 진행 턴 목록. 발급 이후 진행된 턴은 포함되지 않습니다."),
    )
    val turns: List<ChatShareTurnResponse>,
)

@Schema(description = "공유된 채팅의 진행 턴. 열람에 불필요한 choices·suggestedInputs와 원본 chatId는 싣지 않습니다(스펙 §4-3-11).")
data class ChatShareTurnResponse(
    @field:Schema(
        description = "사용자 입력",
        example = "마법수정에서 아무 빛도 나오지 않았지만, 내려가는 순간 수정이 금 가더니 깨져버렸다.",
    )
    val userInput: String,

    @field:Schema(description = "사용자 입력을 바탕으로 AI가 이어쓴 이야기", example = "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.")
    val aiOutput: String,

    @field:Schema(description = "이번 턴에 도달한 엔딩 이름(도달 아니면 null)", nullable = true, example = "왕좌를 되찾다")
    val reachedEnding: String? = null,

    @field:Schema(description = "생성 시각", example = "2026-06-12T12:10:00Z")
    val createdAt: Instant,
)

@Schema(description = "선택지 생성 응답. 프론트는 상세 재조회로 렌더하며, 이 본문은 저장된 선택지의 참고용이다(스펙 §4-3-3).")
data class ChatChoicesResponse(
    @field:ArraySchema(
        schema = Schema(description = "다음 행동 선택지", example = "주변을 살핀다."),
        arraySchema = Schema(description = "이 턴에 저장된 다음 행동 선택지 목록(3개)"),
    )
    val choices: List<String>,
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

    // 입력 출처 통과 필드(KNK-751). 서버는 **추론하지 않고** 프론트가 준 값을 AI 바디로 통과시키기만 한다.
    // 신뢰 경계이므로 허용값 밖은 400으로 거절한다(오타·미정의 값이 AI 프롬프트로 흘러가지 않도록).
    @field:Pattern(
        regexp = "choice|edited_choice|typed",
        message = "userSource는 choice, edited_choice, typed 중 하나여야 합니다.",
    )
    @field:Schema(
        description = "사용자 입력의 출처. 추천/선택지를 그대로 쓰면 choice, 고쳐 쓰면 edited_choice, 직접 입력하면 typed. " +
            "서버는 판단하지 않고 AI 호출에 그대로 전달하며, 생략하면 전달하지 않습니다.",
        allowableValues = ["choice", "edited_choice", "typed"],
        example = "typed",
        nullable = true,
    )
    val userSource: String? = null,

    // 선택 기록(KNK-819, 스펙 §4-3-3): 사용자가 고른 직전 턴 선택지를 가리킨다.
    // **형식 검증 애노테이션을 붙이지 않는다** — 0·음수·범위 밖·낡은 turnId 전부 400이 아니라 기록만 건너뛴다.
    // 관측이 채팅 전송을 막지 않기 위함이라, @Positive 하나만 붙어도 그 계약이 깨진다.
    @field:Schema(
        description = "고른 선택지가 달린 직전 턴의 ID(채팅 상세 turns[].id). choiceOrder와 함께 보내면 서버가 선택 결과를 " +
            "기록하며, 값이 낡았거나 다른 채팅의 턴이면 거절하지 않고 기록만 생략합니다.",
        example = "3",
        nullable = true,
    )
    val sourceTurnId: Long? = null,

    @field:Schema(
        description = "고른 선택지의 순번. DB와 같은 1부터 시작하는 값이며, 채팅 상세 turns[].choices 배열의 인덱스 + 1입니다. " +
            "범위 밖 값은 400이 아니라 기록 생략으로 처리합니다.",
        example = "1",
        nullable = true,
    )
    val choiceOrder: Int? = null,
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

/**
 * SSE `character_image` 이벤트(스펙 §4-3-3, KNK-943). AI가 스트리밍 중 보낸 이벤트를 그대로 중계한다.
 *
 * URL 키는 `imageUrl`(camelCase)다. **스펙 표기(`image_url`)와 다르다** — 이 스트림의 다른 이벤트가 전부
 * camelCase(`chatId`·`aiOutput`·`reachedEnding`)라 프론트가 이 이벤트만 다른 표기를 쓰게 할 이유가 없고,
 * AI 구현도 직렬화 별칭으로 `imageUrl`을 내보내고 있어 실물과도 일치한다. 스펙 정정은 별도로 진행한다.
 */
@Schema(description = "SSE 인물 이미지 이벤트 예시")
data class ChatStreamCharacterImageEvent(
    @field:Schema(description = "인물 이름", example = "강진우")
    val name: String,

    @field:Schema(description = "인물 이미지 URL", example = "https://cdn.manyak.app/characters/generated/3f2504e0/9b1c.webp")
    val imageUrl: String,
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
