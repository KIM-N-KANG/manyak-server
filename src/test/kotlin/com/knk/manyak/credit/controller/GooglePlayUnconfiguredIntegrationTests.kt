package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.google.GooglePlayPurchaseClient
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GooglePlayUnconfiguredIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @MockitoBean private lateinit var google: GooglePlayPurchaseClient
    @Test fun `미설정 API는 503이고 Google을 호출하지 않는다`() {
        cleaner.cleanAll()
        val user = users.save(User(nickname = "미설정 구매"))
        client.post().uri("/api/v1/users/me/credits/purchases/google")
            .header("Authorization", "Bearer ${jwt.issueAccessToken(user.publicId)}")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("productId" to "if_5000", "purchaseToken" to "test-purchase"))
            .exchange().expectStatus().isEqualTo(503)
        verifyNoInteractions(google)
    }
}
