package com.knk.manyak.search.config

import org.apache.hc.core5.http.Header
import org.apache.hc.core5.http.HttpHeaders
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.message.BasicHeader
import org.apache.hc.core5.util.Timeout
import org.opensearch.client.json.jackson.JacksonJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.OpenSearchTransport
import org.opensearch.client.transport.aws.AwsSdk2Transport
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import java.time.Duration
import java.util.Base64

@ConfigurationProperties("manyak.search.opensearch")
data class StorySearchProperties(
    val endpoint: String = "",
    val region: String = "ap-northeast-2",
    val storyIndex: String = "stories-dev",
    val reindexOnStartup: Boolean = false,
    /** 검색 질의의 응답 한도. 연결을 포함한 전체 시간이다(STORY 4-1-3). 색인과 재색인에는 걸지 않는다. */
    val queryTimeout: Duration = Duration.ofSeconds(3),
    val username: String = "",
    val password: String = "",
    val accessClientId: String = "",
    val accessClientSecret: String = "",
) {
    /**
     * 전환 기간(KNK-2101): terraform이 홈서버 주소로 바꾸기 전까지 dev와 운영은 관리형 도메인을 가리키며 SigV4만 받는다.
     * 주소가 관리형 도메인이면 SigV4로, 아니면 계정과 접근 토큰으로 붙는다. 도메인 삭제 뒤 이 분기와 SigV4 코드를 지운다.
     */
    val awsManaged: Boolean get() = endpoint.trim().endsWith(".amazonaws.com")

    /** 비밀번호와 토큰을 가린다. 설정 값이 로그나 예외에 문자열로 실려도 드러나지 않게 한다. */
    override fun toString(): String =
        "StorySearchProperties(endpoint=$endpoint, region=$region, storyIndex=$storyIndex, reindexOnStartup=$reindexOnStartup, " +
            "queryTimeout=$queryTimeout, username=${mask(username)}, password=${mask(password)}, " +
            "accessClientId=${mask(accessClientId)}, accessClientSecret=${mask(accessClientSecret)})"

    private fun mask(value: String) = if (value.isEmpty()) "" else "****"
}

@Configuration
@EnableConfigurationProperties(StorySearchProperties::class)
class StorySearchConfig {
    // AwsSdk2Transport.close()는 HTTP 클라이언트를 닫지 않아 Spring이 별도로 수명주기를 관리한다. 전환 기간의 SigV4 경로에서만 쓴다.
    @Bean(destroyMethod = "close")
    fun storySearchHttpClient(properties: StorySearchProperties): SdkHttpClient? =
        if (properties.endpoint.isBlank() || !properties.awsManaged) null else ApacheHttpClient.builder()
            .connectionTimeout(Duration.ofSeconds(3))
            .socketTimeout(Duration.ofSeconds(10))
            .build()

    @Bean(destroyMethod = "close")
    fun storySearchTransport(properties: StorySearchProperties, storySearchHttpClient: SdkHttpClient?): OpenSearchTransport? {
        // 값이 아니라 설정 키만 알린다. 엔드포인트가 비어 있어도 확인한다(STORY 6-DEPLOYMENT).
        require(properties.username.isBlank() == properties.password.isBlank()) {
            "manyak.search.opensearch.username과 password는 함께 설정해야 합니다."
        }
        require(properties.accessClientId.isBlank() == properties.accessClientSecret.isBlank()) {
            "manyak.search.opensearch.access-client-id와 access-client-secret은 함께 설정해야 합니다."
        }
        if (properties.endpoint.isBlank()) return null
        require(!properties.endpoint.contains("://") && !properties.endpoint.contains('/')) {
            "manyak.search.opensearch.endpoint는 스킴·경로 없는 호스트여야 합니다."
        }
        if (properties.awsManaged) {
            return AwsSdk2Transport(
                requireNotNull(storySearchHttpClient), properties.endpoint.trim(), Region.of(properties.region),
                AwsSdk2TransportOptions.builder().setMapper(searchJsonMapper()).build(),
            )
        }
        return homeTransport(HttpHost("https", properties.endpoint.trim(), 443), properties)
    }

    @Bean
    fun openSearchClient(storySearchTransport: OpenSearchTransport?): OpenSearchClient? = storySearchTransport?.let(::OpenSearchClient)

    companion object {
        // AWS transport의 NDJSON bulk는 여러 루트 JSON을 쓴다. JSON-B generator는 이를 거부한다.
        // 이 Jackson 2 매퍼는 OpenSearch 전용이며 Spring Jackson 3 빈으로 등록하지 않는다.
        fun searchJsonMapper(): JacksonJsonpMapper =
            JacksonJsonpMapper(com.fasterxml.jackson.databind.ObjectMapper())

        /**
         * 홈서버 OpenSearch 연결(STORY 6-DEPLOYMENT). 계정은 Basic 헤더로 먼저 보내고(401 왕복 없음),
         * Cloudflare Access 서비스 토큰은 `CF-Access-Client-Id`, `CF-Access-Client-Secret` 헤더로 보낸다. 비어 있으면 보내지 않는다.
         */
        fun homeTransport(host: HttpHost, properties: StorySearchProperties): OpenSearchTransport {
            val headers = buildList<Header> {
                if (properties.username.isNotBlank()) {
                    val token = Base64.getEncoder().encodeToString("${properties.username}:${properties.password}".toByteArray())
                    add(BasicHeader(HttpHeaders.AUTHORIZATION, "Basic $token"))
                }
                if (properties.accessClientId.isNotBlank()) {
                    add(BasicHeader("CF-Access-Client-Id", properties.accessClientId))
                    add(BasicHeader("CF-Access-Client-Secret", properties.accessClientSecret))
                }
            }
            return ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(searchJsonMapper())
                .setDefaultHeaders(headers.toTypedArray())
                .setConnectionConfigCallback { it.setConnectTimeout(Timeout.ofSeconds(3)) }
                .setRequestConfigCallback { it.setResponseTimeout(Timeout.ofSeconds(10)) }
                .build()
        }
    }
}
