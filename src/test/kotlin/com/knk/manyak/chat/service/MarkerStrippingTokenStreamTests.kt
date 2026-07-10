package com.knk.manyak.chat.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `token` 스트림에서 이미지 마커를 걸러내는 이중 방어를 검증한다(스펙 §4-3-9).
 *
 * 사용자에게 `[[image:...]]` 원문이 보이는 것은 어떤 경우에도 계약 위반이므로,
 * 마커가 청크 경계에 걸쳐 쪼개져 도착해도 새어나가지 않아야 한다.
 */
class MarkerStrippingTokenStreamTests {

    /** 토큰을 순서대로 넣고 스트림 종료까지 흘러나온 전체 본문을 모은다. */
    private fun stream(vararg tokens: String): String {
        val filter = MarkerStrippingTokenStream()
        return tokens.joinToString(separator = "") { filter.accept(it) } + filter.flush()
    }

    @Test
    fun `마커가 없으면 본문을 그대로 흘린다`() {
        assertThat(stream("문이 열리자 ", "붉은 노을이 번졌다.")).isEqualTo("문이 열리자 붉은 노을이 번졌다.")
    }

    @Test
    fun `한 토큰에 담긴 마커를 걷어낸다`() {
        assertThat(stream("노을이 번졌다.[[image:bg_0007]]그녀가 돌아보았다."))
            .isEqualTo("노을이 번졌다.그녀가 돌아보았다.")
    }

    /** 스트리밍의 핵심 함정 — 마커가 청크 경계에 걸쳐 쪼개져 도착한다. */
    @Test
    fun `여러 토큰에 걸쳐 쪼개진 마커도 걷어낸다`() {
        assertThat(stream("노을.", "[[im", "age:", "bg_00", "07]]", "그녀.")).isEqualTo("노을.그녀.")
    }

    @Test
    fun `한 글자씩 쪼개져도 걷어낸다`() {
        val tokens = "노을.[[image:bg_0007]]그녀.".map { it.toString() }.toTypedArray()

        assertThat(stream(*tokens)).isEqualTo("노을.그녀.")
    }

    @Test
    fun `마커가 여러 개면 모두 걷어낸다`() {
        assertThat(stream("가[[image:bg_0007]]나[[image:char_0031]]다")).isEqualTo("가나다")
    }

    /** 마커가 아닌 대괄호는 본문이므로 살아남아야 한다. */
    @Test
    fun `마커가 아닌 이중 대괄호는 그대로 흘린다`() {
        assertThat(stream("그는 [[각주]]를 남겼다.")).isEqualTo("그는 [[각주]]를 남겼다.")
    }

    @Test
    fun `대문자 키나 공백이 섞인 마커 문법은 본문으로 취급한다`() {
        assertThat(stream("[[image:BG_0007]]")).isEqualTo("[[image:BG_0007]]")
    }

    /** 닫히지 않은 채 스트림이 끝나면 붙들고 있던 꼬리를 본문으로 내보낸다(유실 금지). */
    @Test
    fun `닫히지 않은 마커 후보는 종료 시 본문으로 나온다`() {
        assertThat(stream("노을.", "[[image:bg_00")).isEqualTo("노을.[[image:bg_00")
    }

    @Test
    fun `여는 대괄호 하나로 끝나도 유실되지 않는다`() {
        assertThat(stream("노을.", "[")).isEqualTo("노을.[")
    }

    @Test
    fun `마커 뒤에 곧바로 본문이 이어져도 경계가 정확하다`() {
        assertThat(stream("[[image:bg_0007]]붉은 노을")).isEqualTo("붉은 노을")
    }
}
