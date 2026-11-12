package com.knk.manyak.story.service

import com.knk.manyak.global.observability.StructuredLogger
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 간편 제작 환불 기록기(KNK-2081, STORY 5-4-7). 0 시계열 사전 등록과 등록된 미터에 이어 세는지 고정한다. */
class StoryCreationRefundsTests {

    @Test
    fun `환불 카운터는 증가 이전에 success와 failure 모두 0으로 등록된다`() {
        val registry = SimpleMeterRegistry()

        StoryCreationRefunds(registry, StructuredLogger()).bindTo(registry)

        listOf("success", "failure").forEach { outcome ->
            assertThat(registry.find("manyak.story.creation.refund").tag("outcome", outcome).counter()?.count()).isEqualTo(0.0)
        }
    }

    @Test
    fun `되돌리기 결과를 사전 등록한 미터에 이어 세고 보상 예외는 원래 예외에 덧붙인다`() {
        val registry = SimpleMeterRegistry()
        val refunds = StoryCreationRefunds(registry, StructuredLogger()).also { it.bindTo(registry) }
        val original = IllegalStateException("원래 실패")

        refunds.restore(original, StoryCreationRefunds.Stage.STORY_COMPLETION, StoryCreationRefunds.Requester.GUEST, 1L) { true }
        refunds.restore(original, StoryCreationRefunds.Stage.STORY_COMPLETION, StoryCreationRefunds.Requester.MEMBER_TRIAL, 1L) { false }
        refunds.restore(original, StoryCreationRefunds.Stage.STORY_COMPLETION, StoryCreationRefunds.Requester.GUEST, 1L) {
            throw IllegalArgumentException("보상 실패")
        }

        assertThat(registry.find("manyak.story.creation.refund").tag("outcome", "success").counters()).hasSize(1)
        assertThat(registry.find("manyak.story.creation.refund").tag("outcome", "success").counter()!!.count()).isEqualTo(1.0)
        assertThat(registry.find("manyak.story.creation.refund").tag("outcome", "failure").counter()!!.count()).isEqualTo(2.0)
        assertThat(original.suppressed.map { it.message }).containsExactly("보상 실패")
    }
}
