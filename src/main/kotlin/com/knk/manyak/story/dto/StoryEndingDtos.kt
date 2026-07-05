package com.knk.manyak.story.dto

import com.knk.manyak.story.entity.StoryEnding
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** 엔딩 개수 상한(KNK-419, 시작 설정당 최대 10). */
const val MAX_ENDINGS = 10

@Schema(description = "엔딩 교체 저장 요청. 보낸 목록으로 시작 설정의 엔딩 전체를 대체한다.")
data class ReplaceEndingsRequest(
    // 기본값을 두지 않는다: 필드가 누락되면(`{}`·오타) 역직렬화가 실패해 400이 나야 한다. 기본값(emptyList)을
    // 두면 누락 요청이 명시적 빈 배열과 구분되지 않아 엔딩을 조용히 전부 지운다(silent wipe 방지).
    // 전부 삭제는 명시적 `"endings": []` 로만 허용한다.
    @field:Valid
    @field:Size(max = MAX_ENDINGS, message = "엔딩은 최대 ${MAX_ENDINGS}개까지 등록할 수 있습니다.")
    @field:ArraySchema(
        schema = Schema(implementation = EndingItem::class),
        arraySchema = Schema(description = "엔딩 목록(최대 10). 빈 배열이면 전부 삭제된다. 배열 순서가 표시 순서가 된다."),
    )
    // 원소 @NotNull: `[null]` 본문은 null 원소가 담긴 리스트로 역직렬화되는데, @Valid는 non-null 항목에만
    // 캐스케이드하므로 null이 그대로 replaceEndings까지 내려가 mapIndexed에서 500이 된다. 원소 non-null 제약으로
    // 검증 단계에서 400으로 거른다.
    val endings: List<@NotNull EndingItem>,
)

@Schema(description = "엔딩 입력 항목")
data class EndingItem(
    @field:NotBlank(message = "엔딩 제목은 비어 있을 수 없습니다.")
    @field:Size(max = 100, message = "엔딩 제목은 100자를 넘을 수 없습니다.")
    @field:Schema(description = "엔딩 제목", example = "왕좌를 되찾다")
    val title: String,

    @field:NotBlank(message = "엔딩 내용은 비어 있을 수 없습니다.")
    @field:Schema(description = "엔딩 내용", example = "주인공은 잃어버린 왕좌를 되찾고 새 시대를 연다.")
    val content: String,

    @field:Schema(
        description = "도달 조건(자유 텍스트). 저장만 하며 발동 로직은 범위 밖. 생략하면 null.",
        example = "신뢰도 100 이상",
        nullable = true,
    )
    val conditionText: String? = null,

    @field:Schema(description = "활성화 여부. 생략하면 true.", example = "true", defaultValue = "true")
    val enabled: Boolean = true,
)

fun StoryEnding.toEndingResponse(): StoryEndingResponse =
    StoryEndingResponse(
        title = title,
        content = content,
        conditionText = conditionText,
        sortOrder = sortOrder.toInt(),
        enabled = enabled,
    )
