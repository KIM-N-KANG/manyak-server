package com.knk.manyak.auth.handoff

import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.observability.RequestCorrelationFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인 핸드오프 API(스펙 §4-3-5). 셋 다 인증이 없다 — 인앱은 게스트이고, 외부 랜딩은 아직 로그인 전이다.
 *
 * 코드는 **URL이 아니라 [HEADER_HANDOFF_CODE] 헤더로만** 받는다. 요청 URI는 구조화 로그·Sentry breadcrumb에
 * 남으므로 path·쿼리에 실으면 코드 원문이 로그에 박힌다. 외부 랜딩은 코드를 HttpOnly 쿠키로 옮겨 담고,
 * 이후 BFF 프록시가 그 쿠키를 이 헤더로 주입한다.
 *
 * 소비(이관)는 여기 없다 — `POST /auth/login/google`이 겸한다([LoginHandoffService.consume]).
 */
@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth/handoffs")
class LoginHandoffController(
    private val loginHandoffService: LoginHandoffService,
    private val guestTrialLimitService: GuestTrialLimitService,
) {

    @Operation(
        summary = "로그인 핸드오프 생성",
        description = "인앱 브라우저에서 외부 브라우저로 넘어가기 전에 게스트 스토리·채팅 ID와 원본 디바이스 ID를 " +
            "임시 보관하고 일회용 코드를 발급합니다. 코드는 이 응답에서만 노출되며 이후 헤더로만 제시합니다. " +
            "디바이스 ID는 회원 체험 시드에 쓰이므로 원문 헤더가 필수입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "핸드오프 생성됨",
                content = [Content(schema = Schema(implementation = LoginHandoffCreateResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음(디바이스 헤더 누락·복귀 경로 형식·배열 100개 초과·출처 앱 값)",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: LoginHandoffCreateRequest,
        @RequestHeader(value = RequestCorrelationFilter.HEADER_DEVICE_ID, required = false) deviceId: String?,
    ): LoginHandoffCreateResponse =
        // 게스트 체험 한도 대상 요청과 같은 검증·문구를 재사용한다(헤더 누락은 400).
        loginHandoffService.create(request, guestTrialLimitService.requireDeviceId(deviceId))

    @Operation(
        summary = "로그인 핸드오프 확인",
        description = "외부 브라우저 랜딩이 코드를 검증하고 옮길 건수와 복귀 경로를 받습니다. " +
            "PENDING 상태면 LANDED로 전이합니다. 스토리 제목·채팅 본문 같은 콘텐츠는 노출하지 않습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "유효한 핸드오프",
                content = [Content(schema = Schema(implementation = LoginHandoffSummaryResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "존재하지 않거나 만료된 코드(둘을 구분하지 않음)",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping
    fun confirm(
        @RequestHeader(HEADER_HANDOFF_CODE) handoffCode: String,
    ): LoginHandoffSummaryResponse = loginHandoffService.land(handoffCode)

    @Operation(
        summary = "로그인 핸드오프 상태 조회",
        description = "외부 로그인을 마치고 인앱 브라우저로 돌아왔을 때, 실제로 이관된 ID만 로컬에서 정리하기 위해 " +
            "상태와 이관된 공개 ID 목록을 조회합니다. 이관되지 않은 ID는 여전히 게스트 소유이므로 지우면 안 됩니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "핸드오프 상태",
                content = [Content(schema = Schema(implementation = LoginHandoffStatusResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "존재하지 않거나 만료된 코드(둘을 구분하지 않음)",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/status")
    fun status(
        @RequestHeader(HEADER_HANDOFF_CODE) handoffCode: String,
    ): LoginHandoffStatusResponse = loginHandoffService.status(handoffCode)

    companion object {
        /** 핸드오프 코드 전달 헤더. URL(path·쿼리)에 싣지 않기 위한 유일한 수신 경로다. */
        const val HEADER_HANDOFF_CODE = "X-Manyak-Handoff-Code"
    }
}
