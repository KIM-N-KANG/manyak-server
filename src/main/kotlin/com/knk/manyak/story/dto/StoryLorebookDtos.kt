package com.knk.manyak.story.dto

import com.knk.manyak.story.entity.StoryLorebook
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** 스토리가 참조할 수 있는 로어북 개수 상한(KNK-421). */
const val MAX_STORY_LOREBOOKS = 10

@Schema(description = "스토리 참조 로어북 교체 저장 요청. 보낸 로어북 id 목록으로 스토리의 참조 로어북 전체를 대체한다.")
data class ReplaceStoryLorebooksRequest(
    // 기본값을 두지 않는다: 필드가 누락되면(`{}`·오타) 역직렬화가 실패해 400이 나야 한다. 기본값(emptyList)을
    // 두면 누락 요청이 명시적 빈 배열과 구분되지 않아 참조를 조용히 전부 지운다(silent wipe 방지).
    // 전부 해제는 명시적 `"lorebookIds": []` 로만 허용한다.
    @field:Size(max = MAX_STORY_LOREBOOKS, message = "참조 로어북은 최대 ${MAX_STORY_LOREBOOKS}개까지 등록할 수 있습니다.")
    @field:ArraySchema(
        schema = Schema(type = "integer", format = "int64", example = "1"),
        arraySchema = Schema(
            description = "참조할 로어북 id 목록(최대 10). 카탈로그의 활성 로어북 id여야 한다. " +
                "빈 배열이면 전부 해제된다. 배열 순서가 표시 순서가 된다.",
        ),
    )
    // 원소 @NotNull: `[null]` 본문은 null 원소가 담긴 리스트로 역직렬화되므로, 원소 non-null 제약으로 400으로 거른다.
    val lorebookIds: List<@NotNull Long>,
)

fun StoryLorebook.toLorebookResponse(): LorebookResponse =
    LorebookResponse(
        id = lorebook.id,
        name = lorebook.name,
        genre = lorebook.genre,
        content = lorebook.content,
    )
