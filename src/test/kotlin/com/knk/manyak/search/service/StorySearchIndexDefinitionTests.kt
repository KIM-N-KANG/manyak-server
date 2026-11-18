package com.knk.manyak.search.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper

/**
 * 운영 인덱스 정의의 품사 제거 태그(KNK-2101). OpenSearch 3.x(Lucene 10) nori에는 묶음 태그 `E`, `J`가 없어 인덱스 생성이 400이다.
 * 테스트 컨테이너에는 nori가 없어 생성으로 확인할 수 없으므로 정의 파일을 직접 본다.
 */
class StorySearchIndexDefinitionTests {
    @Test
    fun `nori 품사 제거 태그는 Lucene 10에 있는 세분 태그로 조사와 어미를 모두 뺀다`() {
        val root = ClassPathResource("opensearch/stories-index.json").inputStream.use { ObjectMapper().readTree(it) }
        val node = root.path("settings").path("analysis").path("filter").path("korean_pos").path("stoptags")
        val stoptags = (0 until node.size()).map { node[it].asString() }
        assertThat(stoptags).doesNotContain("E", "J")
        assertThat(stoptags).contains("EP", "EF", "EC", "ETN", "ETM", "JKS", "JKC", "JKG", "JKO", "JKB", "JKV", "JKQ", "JX", "JC")
    }
}
