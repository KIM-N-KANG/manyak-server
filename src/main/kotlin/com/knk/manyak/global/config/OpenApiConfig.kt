package com.knk.manyak.global.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openAPI(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Manyak Server API")
                    .description("Manyak backend API documentation")
                    .version("v1"),
            )
            // 서버 주소는 상대 주소 하나로 둔다(KNK-2124). springdoc이 만드는 주소는 요청 scheme을 따르는데, TLS를 끝내는
            // kamal-proxy 뒤라 http로 나와 https 화면의 Try it out이 막혔다. 상대 주소면 Swagger UI가 화면과 같은 출처로 부른다.
            // 요청 처리(forward-headers-strategy)는 바꾸지 않는다. 관리자 웹 추출과 웹 Orval 생성은 servers를 쓰지 않는다.
            .servers(listOf(Server().url("/")))
            // Bearer(JWT) 보안 스킴을 등록한다(KNK-450). Swagger UI에 Authorize 버튼이 뜨고, 토큰을 넣어 인증
            // 엔드포인트를 시도할 수 있다. 인증 요구 표시(자물쇠)는 인증 필수 엔드포인트의 @SecurityRequirement가 건다.
            // 전역 SecurityRequirement는 걸지 않는다 — 대부분의 도메인 엔드포인트가 공개(permitAll·optional 인증)이므로
            // 전역으로 걸면 공개 엔드포인트까지 인증 필수로 오표시된다.
            .components(
                Components().addSecuritySchemes(
                    SECURITY_SCHEME_NAME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("access 토큰(HS256 JWT). `Authorization: Bearer <accessToken>` 로 보낸다."),
                ),
            )

    // Swagger UI 태그 묶음 순서(KNK-2124). 앱 태그를 이름순으로 두고, 관리자 태그(`Admin ` 접두)를 그 뒤에 이름순으로 둔다.
    // UI는 상단 tags 목록 순서를 따른다. 기본은 springdoc 수집 순서라 앱과 관리자 태그가 섞였다. 태그 이름은 바꾸지 않는다.
    @Bean
    fun tagOrderCustomizer() = OpenApiCustomizer { api ->
        api.tags = api.tags?.sortedWith(compareBy({ it.name.startsWith(ADMIN_TAG_PREFIX) }, { it.name }))
    }

    companion object {
        /** 관리자 API 태그 접두. 관리자 컨트롤러 태그는 모두 이 접두로 시작한다. */
        const val ADMIN_TAG_PREFIX = "Admin "

        /** 인증 필수 엔드포인트의 @SecurityRequirement(name = ...)와 일치해야 한다. */
        const val SECURITY_SCHEME_NAME = "bearerAuth"
    }
}
