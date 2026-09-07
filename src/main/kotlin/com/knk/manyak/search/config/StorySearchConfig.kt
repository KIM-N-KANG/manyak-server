package com.knk.manyak.search.config

import org.opensearch.client.json.jsonb.JsonbJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.aws.AwsSdk2Transport
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import java.time.Duration

@ConfigurationProperties("manyak.search.opensearch")
data class StorySearchProperties(
    val endpoint: String = "",
    val region: String = "ap-northeast-2",
    val storyIndex: String = "stories-dev",
    val reindexOnStartup: Boolean = false,
)

@Configuration
@EnableConfigurationProperties(StorySearchProperties::class)
class StorySearchConfig {
    // AwsSdk2Transport.close()는 HTTP 클라이언트를 닫지 않아 Spring이 별도로 수명주기를 관리한다.
    @Bean(destroyMethod = "close")
    fun storySearchHttpClient(properties: StorySearchProperties): SdkHttpClient? =
        if (properties.endpoint.isBlank()) null else ApacheHttpClient.builder()
            .connectionTimeout(Duration.ofSeconds(3))
            .socketTimeout(Duration.ofSeconds(10))
            .build()

    @Bean
    fun openSearchClient(properties: StorySearchProperties, storySearchHttpClient: SdkHttpClient?): OpenSearchClient? {
        if (properties.endpoint.isBlank()) return null
        require(!properties.endpoint.contains("://") && !properties.endpoint.contains('/')) {
            "manyak.search.opensearch.endpoint는 스킴·경로 없는 호스트여야 합니다."
        }
        return OpenSearchClient(
            AwsSdk2Transport(
                requireNotNull(storySearchHttpClient), properties.endpoint.trim(), Region.of(properties.region),
                AwsSdk2TransportOptions.builder().setMapper(JsonbJsonpMapper()).build(),
            ),
        )
    }
}
