package com.knk.manyak.auth.repository

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
class AuthDomainRepositoryTests {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var socialAccountRepository: SocialAccountRepository

    private fun newUser(nickname: String = "만약유저"): User =
        userRepository.save(User(nickname = nickname))

    @Test
    fun `사용자와 소셜계정을 저장하고 provider+provider_user_id로 조회한다`() {
        val user = newUser()
        socialAccountRepository.save(
            SocialAccount(
                userId = user.id,
                provider = SocialProvider.GOOGLE,
                providerUserId = "google-123",
                email = "user@example.com",
            ),
        )

        val found = socialAccountRepository.findByProviderAndProviderUserId(
            SocialProvider.GOOGLE,
            "google-123",
        )

        assertNotNull(found)
        assertEquals(user.id, found!!.userId)
        assertEquals("user@example.com", found.email)
        assertNull(found.lastLoginAt)
    }

    @Test
    fun `user_id로 사용자의 소셜계정들을 조회한다`() {
        val user = newUser()
        socialAccountRepository.save(
            SocialAccount(userId = user.id, provider = SocialProvider.GOOGLE, providerUserId = "g-1"),
        )
        socialAccountRepository.save(
            SocialAccount(userId = user.id, provider = SocialProvider.KAKAO, providerUserId = "k-1"),
        )

        val accounts = socialAccountRepository.findByUserId(user.id)

        assertEquals(
            setOf(SocialProvider.GOOGLE, SocialProvider.KAKAO),
            accounts.map { it.provider }.toSet(),
        )
    }

    @Test
    fun `같은 provider+provider_user_id는 UNIQUE 제약으로 거부된다`() {
        val user = newUser()
        socialAccountRepository.saveAndFlush(
            SocialAccount(userId = user.id, provider = SocialProvider.GOOGLE, providerUserId = "dup-1"),
        )

        assertThrows(DataIntegrityViolationException::class.java) {
            socialAccountRepository.saveAndFlush(
                SocialAccount(userId = user.id, provider = SocialProvider.GOOGLE, providerUserId = "dup-1"),
            )
        }
    }

    @Test
    fun `사용자 저장 시 public_id가 자동 생성되고 public_id로 조회된다`() {
        val user = newUser()

        assertNotNull(user.publicId)
        val found = userRepository.findByPublicId(user.publicId)
        assertNotNull(found)
        assertEquals(user.id, found!!.id)
    }

    @Test
    fun `사용자 public_id는 서로 다르게 생성된다`() {
        val first = newUser("첫째")
        val second = newUser("둘째")

        assertNotEquals(first.publicId, second.publicId)
    }

    @Test
    fun `사용자 저장 시 기본값 ACTIVE 상태가 적용된다`() {
        val user = newUser()

        val found = userRepository.findById(user.id).orElseThrow()
        assertEquals(com.knk.manyak.auth.entity.UserStatus.ACTIVE, found.status)
        assertNull(found.deletedAt)
    }
}
