package com.knk.manyak.global.config

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.global.security.CurrentUserIdArgumentResolver
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Spring MVC 커스텀 설정. @CurrentUserId 파라미터를 해석하는 [CurrentUserIdArgumentResolver]를 등록한다.
 */
@Configuration
class WebMvcConfig(
    private val currentUserIdArgumentResolver: CurrentUserIdArgumentResolver,
) : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUserIdArgumentResolver)
    }

    private companion object {
        init {
            // @CurrentUserId는 서버가 인증 컨텍스트에서 해석하는 내부 파라미터다. OpenAPI(Swagger) 문서에
            // 요청 파라미터로 노출되지 않도록 springdoc이 이 어노테이션이 붙은 파라미터를 무시하게 한다(Codex PR #76 P2).
            SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId::class.java)
        }
    }
}
