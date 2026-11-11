package com.knk.manyak.auth.dto

import java.time.Instant
import java.util.UUID

/** 다른 모듈이 작성자 카드와 소유자 요약에 쓰는 회원 프로필. [com.knk.manyak.auth.service.AccountQueryService]가 돌려준다. */
data class AccountProfile(val publicId: UUID, val nickname: String, val profileImageUrl: String?)

/** 관리자 회원 조회가 public_id로 찾은 회원의 내부 id와 탈퇴 시각. */
data class AdminMemberView(val id: Long, val publicId: UUID, val deletedAt: Instant?)
