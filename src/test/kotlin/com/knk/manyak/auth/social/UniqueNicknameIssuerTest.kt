package com.knk.manyak.auth.social

import com.knk.manyak.auth.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * 가입 시 랜덤 닉네임 발급의 충돌 회피(KNK-1147).
 *
 * 닉네임이 정규화 기준 유일해지면서(V75) 발급도 충돌할 수 있다. 조합 수가 1,600이라 회원이 늘면
 * 실제로 부딪히므로, 다시 뽑아 보고 그래도 안 되면 접미를 붙여 **가입 자체는 실패시키지 않는다**.
 */
class UniqueNicknameIssuerTest {

    private val generator: NicknameGenerator = mock(NicknameGenerator::class.java)
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val issuer = UniqueNicknameIssuer(generator, userRepository)

    @Test
    fun `비어 있는 닉네임이면 그대로 쓴다`() {
        `when`(generator.generate()).thenReturn(GeneratedNickname("몽환적인 이야기꾼", "이야기꾼"))
        `when`(userRepository.existsByNicknameKey(anyString())).thenReturn(false)

        val issued = issuer.issue()

        assertThat(issued.text).isEqualTo("몽환적인 이야기꾼")
        assertThat(issued.noun).isEqualTo("이야기꾼")
    }

    @Test
    fun `이미 쓰는 닉네임이면 다시 뽑는다`() {
        `when`(generator.generate())
            .thenReturn(GeneratedNickname("몽환적인 이야기꾼", "이야기꾼"))
            .thenReturn(GeneratedNickname("고독한 방랑자", "방랑자"))
        `when`(userRepository.existsByNicknameKey(anyString())).thenReturn(true, false)

        assertThat(issuer.issue().text).isEqualTo("고독한 방랑자")
    }

    @Test
    fun `계속 충돌하면 접미를 붙여서라도 발급한다`() {
        // 가입은 막지 않는다 — 여기서 예외를 던지면 조합 고갈이 곧 회원가입 장애가 된다.
        `when`(generator.generate()).thenReturn(GeneratedNickname("몽환적인 이야기꾼", "이야기꾼"))
        `when`(userRepository.existsByNicknameKey(anyString())).thenReturn(true)

        val issued = issuer.issue()

        assertThat(issued.text).startsWith("몽환적인 이야기꾼#")
        assertThat(issued.text.length).isLessThanOrEqualTo(RandomNicknameGenerator.MAX_NICKNAME_LENGTH)
        // 프리셋 매핑 키(명사)는 접미와 무관하게 원본이어야 한다.
        assertThat(issued.noun).isEqualTo("이야기꾼")
    }
}
