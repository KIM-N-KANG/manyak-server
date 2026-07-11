package com.knk.manyak.auth.social

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

/**
 * RandomNicknameGenerator의 발급 계약을 고정한다.
 *
 * - "형용사 공백 명사" 형식이며 두 토큰은 각각 사전 정의된 풀에서 나온다.
 * - 반환된 [GeneratedNickname.noun]은 text의 명사 토큰과 일치한다(프리셋 이미지 매핑 키).
 * - 항상 50자(VARCHAR(50)) 이내다.
 * - 반복 호출 시 값이 하나로 고정되지 않는다(랜덤성).
 */
class RandomNicknameGeneratorTest {

    private val generator = RandomNicknameGenerator()

    @RepeatedTest(50)
    fun `형용사와 명사를 공백으로 이어 만들고 명사를 함께 반환한다`() {
        val generated = generator.generate()
        val parts = generated.text.split(" ")

        assertThat(parts).hasSize(2)
        assertThat(RandomNicknameGenerator.ADJECTIVES).contains(parts[0])
        assertThat(RandomNicknameGenerator.NOUNS).contains(parts[1])
        // 매핑 키로 쓰는 noun이 실제 text의 명사와 일치해야 한다(프리셋 이미지 배정 근거).
        assertThat(generated.noun).isEqualTo(parts[1])
        assertThat(RandomNicknameGenerator.NOUNS).contains(generated.noun)
    }

    @RepeatedTest(50)
    fun `닉네임은 50자 이내다`() {
        assertThat(generator.generate().text.length).isLessThanOrEqualTo(RandomNicknameGenerator.MAX_NICKNAME_LENGTH)
    }

    @Test
    fun `반복 호출하면 서로 다른 닉네임이 나온다`() {
        val generated = (1..100).map { generator.generate().text }.toSet()

        assertThat(generated.size).isGreaterThan(1)
    }
}
