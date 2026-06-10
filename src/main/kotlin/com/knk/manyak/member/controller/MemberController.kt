package com.knk.manyak.member.controller

import com.knk.manyak.member.dto.MyInfoResponse
import com.knk.manyak.member.service.MemberService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Members", description = "회원 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1")
class MemberController(
    private val memberService: MemberService,
) {

    @Operation(
        summary = "내 정보 조회",
        description = "현재 로그인한 사용자의 기본 정보를 조회합니다.",
    )
    @GetMapping("/me")
    fun getMyInfo(): MyInfoResponse = memberService.getMyInfo()
}
