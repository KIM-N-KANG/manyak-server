package com.knk.manyak.global.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
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

    companion object {
        /** 인증 필수 엔드포인트의 @SecurityRequirement(name = ...)와 일치해야 한다. */
        const val SECURITY_SCHEME_NAME = "bearerAuth"
    }
}
