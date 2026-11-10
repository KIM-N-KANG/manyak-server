package com.knk.manyak.auth.consent

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.ScriptedAiIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 인증 경로의 동의 필요 여부 판정(동의 게이트, 동의 완료)을 경우별로 고정한다(ACCOUNT 4-1-13, 5-1-3).
 *
 * 필요 여부는 문서마다 현행 버전 행이 있는지로만 정해진다. 같은 판정을 하는 회원 조회 [UserConsentService.getConsents]와
 * 결과가 같아야 한다.
 */
class UserConsentAuthenticationStatusIntegrationTests : ScriptedAiIntegrationTest() {
    @Autowired private lateinit var service: UserConsentService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Value("\${manyak.legal.terms-version}") private lateinit var termsVersion: String
    @Value("\${manyak.legal.privacy-version}") private lateinit var privacyVersion: String

    @BeforeEach fun setUp() = cleaner.cleanAll()

    private fun user(): Long = users.save(User(nickname = "동의판정", status = UserStatus.ACTIVE)).id

    private fun agree(userId: Long, type: ConsentDocType, version: String) {
        jdbc.update(
            "INSERT INTO user_consents (user_id, doc_type, version, agreed_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
            userId, type.name, version,
        )
    }

    private fun agreeAllCurrent(userId: Long) {
        agree(userId, ConsentDocType.TERMS, termsVersion)
        agree(userId, ConsentDocType.PRIVACY, privacyVersion)
        agree(userId, ConsentDocType.AGE14, "1")
    }

    /** terms, privacy, age14 순서의 필요 여부. 버전은 늘 현행이고 회원 조회와 같은 결과인지도 본다. */
    private fun needs(userId: Long): List<Boolean> {
        val status = service.authenticationStatus(userId)
        assertThat(listOf(status.terms.requiredVersion, status.privacy.requiredVersion, status.age14.requiredVersion))
            .containsExactly(termsVersion, privacyVersion, "1")
        assertThat(status).isEqualTo(service.getConsents(userId))
        return listOf(status.terms.needsConsent, status.privacy.needsConsent, status.age14.needsConsent)
    }

    @Test
    fun `회원이 없으면 세 항목 모두 필요하고 현행 버전을 돌려준다`() {
        val status = service.authenticationStatus(null)

        assertThat(status).isEqualTo(
            UserConsentResponse(
                ConsentStatusResponse(termsVersion, true),
                ConsentStatusResponse(privacyVersion, true),
                ConsentStatusResponse("1", true),
            ),
        )
    }

    @Test
    fun `동의 기록이 없으면 세 항목 모두 필요하다`() {
        assertThat(needs(user())).containsExactly(true, true, true)
    }

    @Test
    fun `현행 버전에 동의한 항목만 필요 없다`() {
        val userId = user()
        agree(userId, ConsentDocType.PRIVACY, privacyVersion)

        assertThat(needs(userId)).containsExactly(true, false, true)
    }

    @Test
    fun `옛 버전에만 동의했으면 다시 필요하다`() {
        val userId = user()
        agree(userId, ConsentDocType.TERMS, "v0.1")
        agree(userId, ConsentDocType.PRIVACY, "v0.1")
        agree(userId, ConsentDocType.AGE14, "0")

        assertThat(needs(userId)).containsExactly(true, true, true)
    }

    @Test
    fun `옛 버전 이력이 있어도 현행 버전에 동의했으면 필요 없다`() {
        val userId = user()
        agree(userId, ConsentDocType.TERMS, "v0.1")
        agree(userId, ConsentDocType.PRIVACY, "v0.1")
        agreeAllCurrent(userId)

        assertThat(needs(userId)).containsExactly(false, false, false)
    }

    @Test
    fun `다른 문서의 현행 버전 값은 이 문서의 동의로 치지 않는다`() {
        val userId = user()
        agree(userId, ConsentDocType.TERMS, privacyVersion)
        agree(userId, ConsentDocType.PRIVACY, termsVersion)
        agree(userId, ConsentDocType.TERMS, "1")

        assertThat(needs(userId)).containsExactly(true, true, true)
    }

    @Test
    fun `버전은 대소문자와 공백까지 정확히 같아야 한다`() {
        val userId = user()
        agree(userId, ConsentDocType.TERMS, termsVersion.uppercase())
        agree(userId, ConsentDocType.PRIVACY, "$privacyVersion ")

        assertThat(needs(userId)).containsExactly(true, true, true)
    }

    @Test
    fun `다른 회원의 동의는 치지 않는다`() {
        val other = user()
        agreeAllCurrent(other)

        assertThat(needs(user())).containsExactly(true, true, true)
    }
}
