package com.knk.manyak.search

import org.opensearch.client.json.jsonb.JsonbJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.OpenSearchException
import org.opensearch.client.opensearch._types.mapping.TypeMapping
import org.opensearch.client.opensearch.indices.CreateIndexRequest
import org.opensearch.client.opensearch.indices.IndexSettings
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class StorySearchIndexAdmin(private val client: OpenSearchClient?, private val properties: StorySearchProperties) {
    fun ensureIndex() {
        val searchClient = client ?: return
        if (searchClient.indices().exists { it.index(properties.storyIndex) }.value()) return
        try {
            searchClient.indices().create(createRequest(properties.storyIndex))
        } catch (ex: OpenSearchException) {
            // 여러 태스크가 첫 색인·재색인을 동시에 시작해도 인덱스 생성 경합만 멱등 처리한다.
            if (ex.error().type() != "resource_already_exists_exception") throw ex
        }
    }

    companion object {
        fun createRequest(index: String, standardAnalyzer: Boolean = false): CreateIndexRequest {
            val mapper = JsonbJsonpMapper()
            var json = ClassPathResource("opensearch/stories-index.json").inputStream.bufferedReader().use { it.readText() }
            // nori 없는 일회용 테스트 이미지에서만 쓴다. 운영 매핑에는 이 폴백을 적용하지 않는다.
            if (standardAnalyzer) {
                json = json.replace("\"analyzer\": \"korean\"", "\"analyzer\": \"standard\"")
            }
            val root = mapper.jsonProvider().createReader(json.reader()).use { it.readObject() }
            val mappings = mapper.jsonProvider().createParser(root.getJsonObject("mappings").toString().reader()).use {
                TypeMapping._DESERIALIZER.deserialize(it, mapper)
            }
            val builder = CreateIndexRequest.Builder().index(index).mappings(mappings)
            if (!standardAnalyzer) {
                val settings = mapper.jsonProvider().createParser(root.getJsonObject("settings").toString().reader()).use {
                    IndexSettings._DESERIALIZER.deserialize(it, mapper)
                }
                builder.settings(settings)
            }
            return builder.build()
        }
    }
}
