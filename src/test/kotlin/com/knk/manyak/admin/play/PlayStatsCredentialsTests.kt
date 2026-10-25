package com.knk.manyak.admin.play

import com.google.auth.oauth2.UserCredentials
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

class PlayStatsCredentialsTests {
    @Test fun `사용자 계정 자격 JSON도 Play 보고서 자격으로 읽는다`() {
        val json = """{"type":"authorized_user","client_id":"fake-client","client_secret":"fake-secret","refresh_token":"fake-refresh"}"""
        val credentials = playStatsCredentials(Base64.getEncoder().encodeToString(json.toByteArray()))
        assertThat(credentials).isInstanceOf(UserCredentials::class.java)
        assertThat((credentials as UserCredentials).clientId).isEqualTo("fake-client")
    }
}
