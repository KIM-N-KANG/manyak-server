package com.knk.manyak.credit.controller

import com.knk.manyak.credit.dto.TrialsResponse
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.credit.service.GuestTrialLimitService.Counter
import com.knk.manyak.global.security.CurrentUserId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class TrialController(private val trials: GuestTrialLimitService) {
    @Operation(summary = "체험 사용량·한도 조회", description = "인증 선택. 게스트는 X-Manyak-Device-Id 필수. 회원 스토리라인 limit은 null(무제한).")
    @GetMapping("/api/v1/users/me/trials")
    fun getTrials(
        @CurrentUserId userId: Long?,
        @RequestHeader(name = "X-Manyak-Device-Id", required = false) deviceId: String?,
    ): TrialsResponse = TrialsResponse(
        chatTurn = trials.usage(userId, deviceId, Counter.CHAT_TURN),
        chatImage = trials.usage(userId, deviceId, Counter.CHAT_IMAGE),
        storyCreation = trials.usage(userId, deviceId, Counter.STORY_CREATION),
        storylineGeneration = trials.usage(userId, deviceId, Counter.STORYLINE_GENERATION),
    )
}
