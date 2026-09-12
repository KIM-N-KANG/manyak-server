package com.knk.manyak.auth.social

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

/**
 * RandomNicknameGenerator의 발급 계약을 고정한다.
 *
 * - "형용사+명사" 형식이며 두 토큰은 각각 사전 정의된 풀에서 나온다.
 * - 반환된 [GeneratedNickname.noun]은 text의 명사 토큰과 일치한다(프리셋 이미지 매핑 키).
 * - 항상 50자(VARCHAR(50)) 이내다.
 * - 반복 호출 시 값이 하나로 고정되지 않는다(랜덤성).
 */
class RandomNicknameGeneratorTest {

    private val generator = RandomNicknameGenerator()

    @RepeatedTest(50)
    fun `형용사와 명사를 공백 없이 이어 만들고 명사를 함께 반환한다`() {
        val generated = generator.generate()
        assertThat(generated.text).doesNotContain(" ")
        assertThat(generated.text).endsWith(generated.noun)
        assertThat(RandomNicknameGenerator.ADJECTIVES).contains(generated.text.removeSuffix(generated.noun))
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
