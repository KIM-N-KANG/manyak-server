package com.knk.manyak.chat.service

/**
 * `token` 스트림에서 이미지 마커를 걸러내는 이중 방어(스펙 §4-3-9).
 *
 * 스펙은 "AI가 스트림에는 마커 없는 순수 본문만 흘린다"로 AI에 책임을 지웠다. 다만 AI가 실수로 흘리면
 * 사용자 화면에 `[[image:...]]` 원문이 그대로 뜨고, 그건 어떤 경우에도 계약 위반이다. 비용이 거의 없으므로
 * 서버가 한 겹 더 막는다. `completed`의 확정본에는 마커가 그대로 실린다(본문 무변경).
 *
 * 마커는 청크 경계에 걸쳐 쪼개져 도착할 수 있다(`"…[[im"` + `"age:bg_0007]]…"`). 그래서 마커가 될 수 있는
 * 꼬리는 붙들고 있다가, 마커로 확정되면 버리고 아니면 그대로 흘린다. 스트림이 끝나면 [flush]로 남은 꼬리를 낸다.
 */
class MarkerStrippingTokenStream {

    private val pending = StringBuilder()

    /** 토큰을 넣고, 밖으로 내보내도 안전한 부분만 돌려준다. 마커가 될 수 있는 꼬리는 내부에 남는다. */
    fun accept(token: String): String {
        pending.append(token)
        val emitted = StringBuilder()

        while (true) {
            val start = pending.indexOf(MARKER_OPEN)
            if (start < 0) {
                // 여는 괄호가 없다. 다만 꼬리가 `[`로 시작하는 미완성 여는 괄호일 수 있어 그만큼만 붙든다.
                val safeLength = pending.length - heldBackOpenPrefixLength()
                emitted.append(pending, 0, safeLength)
                pending.delete(0, safeLength)
                break
            }

            emitted.append(pending, 0, start)
            pending.delete(0, start)

            val close = pending.indexOf(MARKER_CLOSE, MARKER_OPEN.length)
            if (close < 0) {
                // 아직 닫히지 않았다. 마커가 될 가능성이 남아 있으면 붙들고, 아니면 여는 괄호를 흘려보낸다.
                if (PARTIAL_MARKER_PATTERN.matches(pending)) {
                    break
                }
                emitted.append(pending, 0, MARKER_OPEN.length)
                pending.delete(0, MARKER_OPEN.length)
                continue
            }

            val candidate = pending.substring(0, close + MARKER_CLOSE.length)
            // 완성된 마커면 버리고, `[[각주]]` 같은 남남이면 본문이므로 그대로 흘린다.
            if (!ChatImageBundler.MARKER_PATTERN.matches(candidate)) {
                emitted.append(candidate)
            }
            pending.delete(0, candidate.length)
        }

        return emitted.toString()
    }

    /** 스트림 종료 시 붙들고 있던 꼬리를 내보낸다(완성되지 못한 마커 후보는 본문이었다는 뜻이다). */
    fun flush(): String = pending.toString().also { pending.clear() }

    /** 꼬리가 `[[image:` 의 앞부분과 겹치는 길이. 그만큼은 아직 내보내면 안 된다. */
    private fun heldBackOpenPrefixLength(): Int {
        val maxOverlap = minOf(pending.length, MARKER_OPEN.length - 1)
        for (length in maxOverlap downTo 1) {
            if (pending.substring(pending.length - length) == MARKER_OPEN.substring(0, length)) {
                return length
            }
        }
        return 0
    }

    private companion object {
        const val MARKER_OPEN = "[["
        const val MARKER_CLOSE = "]]"

        /**
         * 닫히지 않은 상태에서 아직 마커가 될 수 있는가(`[[`, `[[im`, `[[image:bg_00`, `[[image:bg_0007]` …).
         *
         * 끝의 `]?`가 중요하다. 한 글자씩 흘러오면 닫는 대괄호도 하나씩 도착하는데, 첫 `]`에서 후보 자격을
         * 잃으면 그 직전까지의 `[[`가 본문으로 새어나간다.
         */
        val PARTIAL_MARKER_PATTERN = Regex("""\[\[(i(m(a(g(e(:[a-z0-9_]{0,64}]?)?)?)?)?)?)?""")
    }
}
