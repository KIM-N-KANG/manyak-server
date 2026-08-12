package com.knk.manyak.chat.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.text.Normalizer

/**
 * 선택 기록의 편집 여부 판정에 쓰는 정규화 규칙(KNK-819, 스펙 §4-3-3) 단위 검증.
 *
 * 규칙은 유니코드 NFC → 앞뒤 공백 제거 → 내부 공백 런을 스페이스 하나로 축약이다.
 * 구두점은 무시하지 않고, 공백을 전부 제거하지도 않는다 — 둘 다 서로 다른 선택지를 같게 만들 수 있다.
 */
class ChoiceTextNormalizationTests {

    private fun sameAs(left: String, right: String): Boolean =
        ChatTurnPersister.normalizeForComparison(left) == ChatTurnPersister.normalizeForComparison(right)

    @Test
    fun `앞뒤 공백 차이는 같은 문장으로 본다`() {
        assertThat(sameAs(ORIGINAL, "  $ORIGINAL  ")).isTrue()
    }

    @Test
    fun `개행은 스페이스로 접혀 같은 문장이 된다`() {
        assertThat(sameAs(ORIGINAL, ORIGINAL.replace(" ", "\n"))).isTrue()
    }

    @Test
    fun `연속 공백은 하나로 축약된다`() {
        assertThat(sameAs(ORIGINAL, ORIGINAL.replace(" ", "     "))).isTrue()
    }

    @Test
    fun `전각 공백도 공백으로 취급한다`() {
        // U+3000. Regex의 기본 \s는 이 문자를 잡지 않으므로 유니코드 공백까지 접는지 고정한다.
        assertThat(sameAs(ORIGINAL, ORIGINAL.replace(" ", "　"))).isTrue()
    }

    @Test
    fun `조합형과 완성형 한글은 같은 문장이다`() {
        // 일부 입력기·플랫폼이 자모 분리(NFD)로 보내므로 정규화가 흡수해야 한다.
        val decomposed = Normalizer.normalize(ORIGINAL, Normalizer.Form.NFD)
        assertThat(decomposed).isNotEqualTo(ORIGINAL) // 전제: 두 표현이 실제로 다른 문자열이다.
        assertThat(sameAs(ORIGINAL, decomposed)).isTrue()
    }

    @Test
    fun `구두점이 다르면 다른 문장이다`() {
        assertThat(sameAs(ORIGINAL, ORIGINAL.replace(".", "!"))).isFalse()
        assertThat(sameAs(ORIGINAL, ORIGINAL.removeSuffix("."))).isFalse()
    }

    @Test
    fun `공백을 전부 지우지는 않는다`() {
        // 태그 정규화(StoryCreationTag.normalize)를 재사용하면 이 둘이 같아진다 — 규칙을 갈라 둔 이유.
        assertThat(sameAs(ORIGINAL, ORIGINAL.replace(" ", ""))).isFalse()
    }

    private companion object {
        const val ORIGINAL = "문을 연다."
    }
}
