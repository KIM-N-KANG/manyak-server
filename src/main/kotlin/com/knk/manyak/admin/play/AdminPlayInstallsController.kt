package com.knk.manyak.admin.play

import com.knk.manyak.global.config.OpenApiConfig
import com.knk.manyak.global.error.ApiErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/dashboard")
class AdminPlayInstallsController(private val service: AdminPlayInstallsService) {
    @Operation(summary = "Play 설치 지표", description = "Play 기준 날짜를 그대로 반환합니다. 보고서는 며칠 늦게 갱신되며 결과는 1시간 캐시합니다.")
    @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    @GetMapping("/play-installs")
    fun installs() = service.dashboard()

    @ExceptionHandler(PlayStatsUnavailable::class)
    fun unavailable(request: HttpServletRequest) = ResponseEntity.status(503).body(ApiErrorResponse(
        status = 503, code = "SERVICE_UNAVAILABLE", message = "설치 지표를 가져올 수 없습니다.", path = request.requestURI))
}
