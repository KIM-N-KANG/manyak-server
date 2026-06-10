package com.knk.manyak.auth.service

import com.knk.manyak.auth.dto.GoogleLoginRequest
import com.knk.manyak.auth.dto.LoginResponse
import com.knk.manyak.auth.dto.LoginUserResponse
import org.springframework.stereotype.Service

@Service
class AuthService {

    fun loginWithGoogle(request: GoogleLoginRequest): LoginResponse {
        check(request.idToken.isNotBlank())

        return LoginResponse(
            accessToken = "mock-access-token",
            refreshToken = "mock-refresh-token",
            expiresIn = 3600,
            user = LoginUserResponse(
                id = 1L,
                nickname = "manyak_writer",
                profileImageUrl = null,
            ),
        )
    }
}
