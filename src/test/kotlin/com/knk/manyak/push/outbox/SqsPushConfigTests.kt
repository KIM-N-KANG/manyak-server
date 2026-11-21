package com.knk.manyak.push.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.TestConfiguration
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import tools.jackson.databind.ObjectMapper
import java.time.Duration

class SqsPushConfigTests {
    /**
     * 브로커는 `manyak.push.broker`로 고른다(KNK-2125). 프로필별 기본값은 yml에 있으므로
     * `application.yml`과 `application-{profile}.yml`을 실제 기동처럼 깔아 둔다(프로필 파일이 이긴다).
     */
    private fun runner(profile: String) = ApplicationContextRunner()
        .withInitializer { context ->
            context.environment.setActiveProfiles(profile)
            val loader = YamlPropertySourceLoader()
            for (file in listOf("application-$profile.yml", "application.yml")) {
                val resource = ClassPathResource(file)
                if (resource.exists()) loader.load(file, resource).forEach { context.environment.propertySources.addLast(it) }
            }
        }
        .withUserConfiguration(SqsPushConfig::class.java, KafkaPushConfig::class.java, PushPublisherConfigTestSettings::class.java)
        .withBean(ObjectMapper::class.java, { ObjectMapper() })
        .withPropertyValues("manyak.push.region=ap-northeast-2")

    @Test
    fun `dev remote도 broker=kafka면 Kafka만 등록하고 큐 URL을 요구하지 않는다`() {
        runner("dev").withPropertyValues("manyak.push.mode=remote", "manyak.push.broker=kafka").run {
            assertThat(it).hasNotFailed().hasSingleBean(PushPublisher::class.java)
            assertThat(it).hasSingleBean(KafkaPushPublisher::class.java).doesNotHaveBean(SqsAsyncClient::class.java)
        }
    }

    @Test
    fun `local remote도 broker=sqs면 SQS만 등록한다`() {
        runner("local").withPropertyValues("manyak.push.mode=remote", "manyak.push.broker=sqs", "manyak.push.queue-url=https://sqs.example.test/push").run {
            assertThat(it).hasNotFailed().hasSingleBean(SqsPushPublisher::class.java).doesNotHaveBean(KafkaPushPublisher::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "prod"])
    fun `dev와 prod remote는 SQS만 등록하고 전송 제한을 설정한다`(profile: String) {
        runner(profile).withPropertyValues("manyak.push.mode=remote", "manyak.push.queue-url=https://sqs.example.test/push")
            .run {
                assertThat(it).hasNotFailed().hasSingleBean(PushPublisher::class.java)
                assertThat(it).hasSingleBean(SqsPushPublisher::class.java).doesNotHaveBean(KafkaPushPublisher::class.java)
                val config = it.getBean(SqsAsyncClient::class.java).serviceClientConfiguration()
                assertThat(config.region().id()).isEqualTo("ap-northeast-2")
                assertThat(config.overrideConfiguration().apiCallTimeout()).hasValue(Duration.ofSeconds(10))
                assertThat(config.overrideConfiguration().apiCallAttemptTimeout()).hasValue(Duration.ofSeconds(5))
            }
    }

    @Test
    fun `local remote는 Kafka만 등록하고 큐 URL을 요구하지 않는다`() {
        runner("local").withPropertyValues("manyak.push.mode=remote").run {
            assertThat(it).hasNotFailed().hasSingleBean(PushPublisher::class.java)
            assertThat(it).hasSingleBean(KafkaPushPublisher::class.java).doesNotHaveBean(SqsAsyncClient::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["local", "dev", "prod", "test"])
    fun `remote가 아니거나 모드 미설정이면 발행기가 없다`(profile: String) {
        for (properties in listOf(emptyArray<String>(), arrayOf("manyak.push.mode=local"))) {
            runner(profile).withPropertyValues(*properties).run {
                assertThat(it).hasNotFailed().doesNotHaveBean(PushPublisher::class.java)
                assertThat(it).doesNotHaveBean(SqsAsyncClient::class.java)
            }
        }
    }

    @Test
    fun `test remote는 브로커를 등록하지 않는다`() {
        runner("test").withPropertyValues("manyak.push.mode=remote").run {
            assertThat(it).hasNotFailed().doesNotHaveBean(PushPublisher::class.java)
            assertThat(it).doesNotHaveBean(SqsAsyncClient::class.java)
        }
    }

    @Test
    fun `dev remote는 큐 URL이 누락되거나 공백이면 기동 실패한다`() {
        for (properties in listOf(emptyArray<String>(), arrayOf("manyak.push.queue-url="), arrayOf("manyak.push.queue-url=   "))) {
            runner("dev").withPropertyValues("manyak.push.mode=remote").withPropertyValues(*properties).run {
                assertThat(it).hasFailed()
                assertThat(it.startupFailure).hasRootCauseMessage("manyak.push.queue-url must not be blank in remote mode")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["10s", "9s"])
    fun `아웃박스 전송 제한이 SQS 호출 제한 이하이면 기동 실패한다`(timeout: String) {
        runner("dev").withPropertyValues(
            "manyak.push.mode=remote", "manyak.push.queue-url=https://sqs.example.test/push",
            "manyak.push.outbox.send-timeout=$timeout",
        ).run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).hasRootCauseMessage("outbox send-timeout must exceed SQS apiCallTimeout (10s)")
        }
    }

}

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushOutboxProperties::class)
class PushPublisherConfigTestSettings
