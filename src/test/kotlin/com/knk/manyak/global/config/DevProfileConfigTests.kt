package com.knk.manyak.global.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

/**
 * KNK-828: 개발(dev) 프로파일이 운영 리소스를 가리키거나 스텁으로 응답하지 않는지 가드한다.
 *
 * dev는 prod를 상속하지 않는 단독 프로파일이라, 잘못은 두 방향으로 난다.
 *  1) 운영 값을 dev에 **넣는다** — 운영 구글 폼 id, 운영 asset base URL, 스텁 on
 *  2) prod 것을 습관적으로 **복사해 온다** — springdoc off, Redis health off
 * 후자는 "설정하지 않는 것"이 정답이라, 여기서는 키의 부재도 통과 조건이다.
 *
 * `./gradlew test`는 프로파일을 test로 강제하고 MANYAK_DB_*를 비우므로(build.gradle.kts) dev 프로파일로
 * 컨텍스트를 띄워 검증할 수 없다. 그래서 SwaggerDocsProdConfigTests와 같은 방식으로 yml 내용을 직접 읽는다
 * (컨텍스트를 만들지 않아 캐시 예산에도 영향이 없다 — SpringContextBudgetGuardTests).
 */
class DevProfileConfigTests {

    private val devProperties = YamlPropertiesFactoryBean()
        .apply { setResources(ClassPathResource("application-dev.yml")) }
        .getObject()!!

    @Test
    fun `개발 프로파일은 AI 스텁을 명시적으로 끈다`() {
        assertThat(devProperties.getProperty("manyak.ai.chat.stub")).isEqualTo("false")
        assertThat(devProperties.getProperty("manyak.ai.story.stub")).isEqualTo("false")
    }

    @Test
    fun `개발 프로파일은 운영 구글 폼 id를 갖지 않는다`() {
        // 운영 폼 id는 application-prod.yml에만 있어야 한다. 여기 값이 생기면 개발 피드백이 운영 시트에 적재된다.
        assertThat(devProperties.getProperty("manyak.google-form.feedback.form-id")).isNullOrEmpty()
    }

    @Test
    fun `개발 프로파일의 프로필 프리셋 base URL은 운영 주소가 아니다`() {
        // 이 값은 profile_image_url로 DB에 영구 저장되므로 한 번 새면 되돌릴 수 없다.
        assertThat(devProperties.getProperty("manyak.asset.profile-preset-base-url"))
            .isNotNull()
            .asString()
            .doesNotContain("https://api.manyak.app")
    }

    @Test
    fun `개발 프로파일은 Swagger 문서를 끄지 않는다`() {
        // prod(KNK-321)만 끈다. dev는 상속하지 않으므로 키가 없는 상태가 정상이다.
        assertThat(devProperties.getProperty("springdoc.api-docs.enabled")).isNotEqualTo("false")
        assertThat(devProperties.getProperty("springdoc.swagger-ui.enabled")).isNotEqualTo("false")
    }

    @Test
    fun `개발 프로파일은 Redis health를 끄지 않는다`() {
        // prod만 배포 사정으로 껐다(application-prod.yml). Spring 기본 true를 그대로 쓴다.
        assertThat(devProperties.getProperty("management.health.redis.enabled")).isNotEqualTo("false")
    }
}
