package com.knk.manyak.story.controller

import com.knk.manyak.global.error.ApiErrorResponse
import com.knk.manyak.story.dto.GenreCatalogResponse
import com.knk.manyak.story.service.GenreCatalogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Stories")
@RestController
@RequestMapping("/api/v1/stories/genres")
class GenreCatalogController(private val genres: GenreCatalogService) {
    @Operation(summary = "제공 장르 조회와 검색", description = "게스트 허용. query 원문 30자 이하, 공백과 대소문자를 무시하고 초성 및 별칭 검색. 빈 질의는 전체, 대표 목록은 항상 반환합니다.")
    @ApiResponse(responseCode = "200", description = "장르 목록", content = [Content(schema = Schema(implementation = GenreCatalogResponse::class))])
    @ApiResponse(responseCode = "400", description = "검색어 30자 초과", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    @GetMapping
    fun get(@RequestParam(required = false) query: String?): GenreCatalogResponse = genres.search(query)
}
