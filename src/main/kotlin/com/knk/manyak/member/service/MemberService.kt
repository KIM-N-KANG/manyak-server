package com.knk.manyak.member.service

import com.knk.manyak.member.dto.MyInfoResponse
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class MemberService {

    fun getMyInfo(): MyInfoResponse =
        MyInfoResponse(
            id = 1L,
            email = "writer@example.com",
            nickname = "manyak_writer",
            profileImageUrl = "https://example.com/profile.png",
            createdAt = Instant.now(),
        )
}
