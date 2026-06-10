package com.knk.manyak.global.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "페이지 목록 응답")
data class PageResponse<T>(
    @field:Schema(description = "목록 데이터")
    val content: List<T>,

    @field:Schema(description = "현재 페이지 번호. 0부터 시작합니다.", example = "0")
    val page: Int,

    @field:Schema(description = "한 페이지에 조회할 데이터 수", example = "20")
    val size: Int,

    @field:Schema(description = "전체 데이터 수", example = "153")
    val totalElements: Long,

    @field:Schema(description = "전체 페이지 수", example = "8")
    val totalPages: Int,
)
