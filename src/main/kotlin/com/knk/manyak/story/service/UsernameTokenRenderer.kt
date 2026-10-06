package com.knk.manyak.story.service

/** 저장 원문의 이름 토큰과 바로 뒤 조사만 한 번 치환한다. */
object UsernameTokenRenderer {
    private val token = Regex("\\{username}(이\\(가\\)|은\\(는\\)|을\\(를\\)|과\\(와\\)|아\\(야\\)|\\(으\\)로|으로\\(로\\)|이랑\\(랑\\))?")

    fun containsToken(text: String): Boolean = token.containsMatchIn(text)

    fun render(text: String, name: String?): String {
        if (name == null) return text
        val last = name.lastOrNull()
        val jong = if (last != null && last in '가'..'힣') (last.code - '가'.code) % 28 else 0
        return token.replace(text) { match ->
            val particle = when (match.groupValues[1]) {
                "이(가)" -> if (jong != 0) "이" else "가"
                "은(는)" -> if (jong != 0) "은" else "는"
                "을(를)" -> if (jong != 0) "을" else "를"
                "과(와)" -> if (jong != 0) "과" else "와"
                "아(야)" -> if (jong != 0) "아" else "야"
                "(으)로", "으로(로)" -> if (jong != 0 && jong != 8) "으로" else "로"
                "이랑(랑)" -> if (jong != 0) "이랑" else "랑"
                else -> ""
            }
            name + particle
        }
    }

    fun truncateRaw(text: String, limit: Int): String {
        require(limit >= 0)
        val crossing = token.findAll(text).firstOrNull { it.range.first < limit && it.range.last >= limit }
        return text.take(crossing?.range?.first ?: limit)
    }
}
