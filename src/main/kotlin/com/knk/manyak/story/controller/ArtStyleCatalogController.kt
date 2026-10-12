package com.knk.manyak.story.controller

import com.knk.manyak.story.dto.ArtStyleCatalogItem
import com.knk.manyak.story.service.ArtStyleCatalogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Stories")
@RestController
@RequestMapping("/api/v1/stories/art-styles")
class ArtStyleCatalogController(private val artStyles: ArtStyleCatalogService) {
    @Operation(summary = "제공 화풍 목록 조회", description = "게스트 허용. 활성 화풍만 노출 순서 오름차순으로 반환합니다.")
    @ApiResponse(responseCode = "200", description = "화풍 목록", content = [Content(array = ArraySchema(schema = Schema(implementation = ArtStyleCatalogItem::class)))])
    @GetMapping
    fun get(): List<ArtStyleCatalogItem> = artStyles.list()
}
