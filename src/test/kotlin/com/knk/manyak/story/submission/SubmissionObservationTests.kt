package com.knk.manyak.story.submission

import com.knk.manyak.global.config.TracingNoiseFilter
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Duration
import java.util.concurrent.Executor

class SubmissionObservationTests {
    @Test
    fun `걸러진 폴러 틱에서 넘긴 작업은 자체 관측 아래 JDBC를 유지한다`() {
        val registry = ObservationRegistry.create()
        val stopped = mutableListOf<Observation.Context>()
        registry.observationConfig().observationPredicate(TracingNoiseFilter.predicate())
            .observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onStop(context: Observation.Context) { stopped.add(context) }
            })
        val row = SubmissionRequested(1L, 2)
        val store = mock(SubmissionClaimStore::class.java) { listOf(row) }
        val runner = mock(SubmissionExecutor::class.java)
        doAnswer {
            val parent = registry.currentObservation
            assertThat(parent?.context?.name).isEqualTo("story.moderation.run")
            Observation.createNotStarted("jdbc.query", registry).observe { }
            null
        }.`when`(runner).run(row)
        val queued = mutableListOf<Runnable>()
        val poller = SubmissionPoller(store, runner, Executor { queued.add(it) }, 1,
            Duration.ofSeconds(300), Duration.ofSeconds(180), true, registry)
        Observation.createNotStarted("tasks.scheduled.execution", registry).observe { poller.poll() }
        assertThat(stopped).isEmpty()
        Observation.createNotStarted("tasks.scheduled.execution", registry).observe { queued.single().run() }
        assertThat(stopped.map { it.name }).containsExactly("jdbc.query", "story.moderation.run")
        assertThat(stopped.first().parentObservation?.contextView?.name).isEqualTo("story.moderation.run")
        assertThat(registry.currentObservation).isNull()
    }
}
