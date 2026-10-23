package com.knk.manyak.admin.infra

import com.knk.manyak.admin.dto.badAdminQuery
import com.knk.manyak.global.error.ApiErrorResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/infra")
@SecurityRequirement(name = "bearerAuth")
class InfraMetricsController(private val metrics: InfraMetricsService, private val database: InfraDatabaseService, private val properties: InfraMetricsProperties) {
    @GetMapping("/metrics") fun metrics(@ParameterObject @ModelAttribute query: InfraMetricsQuery) = metrics.metrics(query)
    @GetMapping("/db-tables") fun tables(
        @io.swagger.v3.oas.annotations.Parameter(schema = io.swagger.v3.oas.annotations.media.Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100"))
        @RequestParam(required = false) limit: String?,
    ): InfraDatabaseResponse {
        val count = if (limit == null) 20 else limit.toIntOrNull() ?: badAdminQuery("limit는 정수여야 합니다.")
        if (count !in 1..properties.databaseMaxLimit) badAdminQuery("limit는 1~100이어야 합니다.")
        return database.tables(count)
    }
    @ExceptionHandler(InfraMetricsUnavailable::class)
    fun unavailable(request: HttpServletRequest) = ResponseEntity.status(503).body(ApiErrorResponse(
        status = 503, code = "SERVICE_UNAVAILABLE", message = "인프라 지표를 가져올 수 없습니다.", path = request.requestURI))
}
