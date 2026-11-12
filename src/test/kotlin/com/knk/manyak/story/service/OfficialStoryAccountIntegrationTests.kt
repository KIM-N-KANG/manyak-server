package com.knk.manyak.story.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** 공식 계정 id 조회(KNK-2056 A-O). 찾은 id만 기억하고, 테스트 DB를 비우면 기억도 지운다. */
class OfficialStoryAccountIntegrationTests : IntegrationTest() {
    @Autowired private lateinit var official: OfficialStoryAccount
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner

    @BeforeEach fun clean() = cleaner.cleanAll()

    private fun saveOfficial() = users.save(User(publicId = UUID.fromString(OFFICIAL_USER_PUBLIC_ID), nickname = "공식"))
    private fun lookups() = mockingDetails(users).invocations.count { it.method.name == "findByPublicId" }

    @Test fun `공식 계정 id를 찾으면 다음 호출은 다시 조회하지 않는다`() {
        val account = saveOfficial()
        clearInvocations(users)
        assertThat(official.officialUserId()).isEqualTo(account.id)
        assertThat(official.officialUserId()).isEqualTo(account.id)
        assertThat(lookups()).isEqualTo(1)
    }

    @Test fun `공식 계정이 없으면 기억하지 않고 생긴 뒤에는 찾는다`() {
        assertThat(official.officialUserId()).isNull()
        val account = saveOfficial()
        assertThat(official.officialUserId()).isEqualTo(account.id)
    }

    @Test fun `테스트 DB를 비우면 기억한 공식 계정 id도 지워 다음 테스트가 새 id를 받는다`() {
        val first = saveOfficial()
        assertThat(official.officialUserId()).isEqualTo(first.id)
        cleaner.cleanAll()
        repeat(3) { users.save(User(nickname = "앞선 회원$it")) }
        val second = saveOfficial()
        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(official.officialUserId()).isEqualTo(second.id)
    }
}
