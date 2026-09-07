package com.knk.manyak.search.event

import com.knk.manyak.search.service.StorySearchIndexAdmin
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.opensearch.client.opensearch.OpenSearchClient

class StorySearchIndexBootstrapTests {
    @Test
    fun `클라이언트가 있으면 재색인 설정과 무관하게 인덱스를 한 번 보장한다`() {
        val admin = mock(StorySearchIndexAdmin::class.java)
        StorySearchIndexBootstrap(mock(OpenSearchClient::class.java), admin).onReady()
        verify(admin, times(1)).ensureIndex()
        verifyNoMoreInteractions(admin)
    }

    @Test
    fun `클라이언트가 없으면 인덱스 보장을 건너뛴다`() {
        val admin = mock(StorySearchIndexAdmin::class.java)
        StorySearchIndexBootstrap(null, admin).onReady()
        verifyNoInteractions(admin)
    }

    @Test
    fun `인덱스 보장 실패는 기동 이벤트로 전파하지 않는다`() {
        val admin = mock(StorySearchIndexAdmin::class.java)
        doThrow(IllegalStateException("unavailable")).`when`(admin).ensureIndex()
        assertDoesNotThrow { StorySearchIndexBootstrap(mock(OpenSearchClient::class.java), admin).onReady() }
        verify(admin).ensureIndex()
    }
}
