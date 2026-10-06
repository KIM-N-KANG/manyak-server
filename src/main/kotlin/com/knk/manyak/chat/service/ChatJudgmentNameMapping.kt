package com.knk.manyak.chat.service

import com.knk.manyak.story.service.UsernameTokenRenderer

/** 요청 후보의 순서를 유지하며 AI 이름만 구분한다. 저장에는 원문 이름을 사용한다. */
class ChatJudgmentNameMapping(names: List<String>, protagonistName: String?) {
    private val toAi: Map<String, String>
    private val toOriginal: Map<String, String>

    init {
        require(names.distinct().size == names.size)
        val displayed = names.map { UsernameTokenRenderer.render(it, protagonistName) }
        val reserved = displayed.toSet()
        val assigned = linkedMapOf<String, String>()
        names.zip(displayed).forEach { (original, display) ->
            var ai = display
            var number = 2
            if (ai in assigned) {
                do { ai = "$display (${number++})" } while (ai in reserved || ai in assigned)
            }
            assigned[ai] = original
        }
        toOriginal = assigned.toMap()
        toAi = assigned.entries.associate { (ai, original) -> original to ai }
    }

    fun aiName(original: String): String = requireNotNull(toAi[original])
    fun originalName(ai: String): String? = toOriginal[ai]
}
