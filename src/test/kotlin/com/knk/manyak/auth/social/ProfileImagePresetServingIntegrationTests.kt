package com.knk.manyak.auth.social

import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 프로필 프리셋 이미지의 공개 정적 서빙을 실제 HTTP로 검증한다(스펙 §4-5 B7, KNK-388).
 *
 * 두 가지 비자명한 위험을 함께 고정한다:
 * - **인증 없이 서빙**돼야 한다(공개 스토리 author.profileImageUrl로 무인증 노출) — SecurityConfig permitAll.
 * - **한글 파일명**이 퍼센트 인코딩된 경로로 실제 리소스에 해소돼야 한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileImagePresetServingIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient

    @Test
    fun `프리셋 이미지는 인증 없이 PNG로 서빙된다`() {
        restTestClient.get()
            .uri("/profile-presets/{noun}.png", "이야기꾼")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(MediaType.IMAGE_PNG)
    }

    @Test
    fun `존재하지 않는 프리셋은 404다`() {
        restTestClient.get()
            .uri("/profile-presets/{noun}.png", "존재하지않는명사")
            .exchange()
            .expectStatus().isNotFound
    }
}
