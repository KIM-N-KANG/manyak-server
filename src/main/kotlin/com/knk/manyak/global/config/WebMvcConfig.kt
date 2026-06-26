package com.knk.manyak.global.config

import com.knk.manyak.global.security.CurrentUserIdArgumentResolver
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
}
