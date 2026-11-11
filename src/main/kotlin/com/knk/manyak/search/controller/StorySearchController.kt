package com.knk.manyak.search.controller

import com.knk.manyak.search.service.StorySearchService
import com.knk.manyak.story.dto.StoryPageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 공개 스토리 검색. 경로와 OpenAPI 태그는 스토리 API(`StoryController`)와 같다. */
@Tag(name = "Stories", description = "스토리 API")
@RestController
@RequestMapping("/api/v1/stories")
class StorySearchController(private val storySearchService: StorySearchService) {

    @Operation(summary = "공개 스토리 검색", description = "검색어는 trim 후 2~100자이며 관련도순으로 반환합니다. 다음 페이지는 같은 q와 nextCursor를 사용합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "검색 성공", content = [Content(schema = Schema(implementation = StoryPageResponse::class))]),
        ApiResponse(responseCode = "400", description = "검색어·커서 형식 오류", content = [Content(schema = Schema(hidden = true))]),
        ApiResponse(responseCode = "503", description = "검색 미설정 또는 일시적 장애", content = [Content(schema = Schema(hidden = true))]),
    ])
    @GetMapping("/search")
    fun searchStories(
        @Parameter(required = true, description = "검색어(trim 후 2~100자)")
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): StoryPageResponse = storySearchService.search(q.orEmpty(), limit, cursor)
}
