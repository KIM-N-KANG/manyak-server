package com.knk.manyak.feedback.controller

import com.knk.manyak.feedback.dto.CreateFeedbackRequest
import com.knk.manyak.feedback.service.FeedbackService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Feedbacks", description = "피드백 API")
@Validated
@RestController
@RequestMapping("/api/v1")
class FeedbackController(
    private val feedbackService: FeedbackService,
) {
    @Operation(
        summary = "피드백 등록",
        description = "사용자 피드백을 등록합니다. 본문만 필수이며, 답변용 이메일은 선택입니다. " +
            "platform/appVersion 은 앱이 자동으로 채워 보내는 메타이며, 로그인 상태면 서버가 user_id 를 채웁니다(인증 도입 후).",
    )
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "400", description = "본문 누락·길이 초과, 잘못된 이메일/플랫폼 형식")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/feedbacks")
    fun createFeedback(
        @Valid @RequestBody request: CreateFeedbackRequest,
    ) = feedbackService.createFeedback(request)
}
