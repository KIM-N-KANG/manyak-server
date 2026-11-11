package com.knk.manyak.global.security

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.auth.dto.UserStatus
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean
import org.springframework.boot.web.servlet.ServletContextInitializerBeans
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.web.FilterChainProxy
import org.springframework.test.web.servlet.client.EntityExchangeResult
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 보안 필터 체인의 구조와 인증 실패 응답, 공개 경로 목록을 고정한다(KNK-2068).
 *
 * global이 도메인 모듈을 참조하지 않도록 보안 코드를 옮기는 동안 동작이 그대로인지 확인하는 안전망이다.
 * 필터는 패키지가 바뀌어도 순서가 같으면 통과하도록 단순 이름으로 비교한다. 의존성 업그레이드로 Spring 기본 필터가
 * 바뀌어도 실패하는데, 보안 경로라 그 변화를 사람이 확인하고 이 목록을 갱신하는 것이 의도다.
 */
class SecurityFilterChainGuardIntegrationTests : IntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: JwtTokenProvider
    @Autowired private lateinit var filterChainProxy: FilterChainProxy
    @Autowired private lateinit var authProperties: AuthProperties
    @Autowired private lateinit var beanFactory: ListableBeanFactory

    private val mapper = ObjectMapper()

    @BeforeEach fun clean() = cleaner.cleanAll()

    @Test
    fun `체인은 관리자 체인과 앱 체인 둘이고 필터 순서가 고정돼 있다`() {
        val chains = filterChainProxy.filterChains
        assertThat(chains).hasSize(2)
        val (admin, app) = chains

        assertThat(admin.matches(request("/api/v1/admin/members"))).isTrue()
        assertThat(admin.matches(request("/api/v1/admin/auth/login/google"))).isTrue()
        assertThat(admin.matches(request("/api/v1/stories"))).isFalse()
        assertThat(app.matches(request("/api/v1/stories"))).isTrue()
        assertThat(app.matches(request("/internal/push-tokens"))).isTrue()

        assertThat(admin.filters.map { it.javaClass.simpleName }).containsExactly(
            "DisableEncodeUrlFilter",
            "WebAsyncManagerIntegrationFilter",
            "SecurityContextHolderFilter",
            "HeaderWriterFilter",
            "CorsFilter",
            "LogoutFilter",
            "AdminAuthenticationFilter",
            "RequestCacheAwareFilter",
            "SecurityContextHolderAwareRequestFilter",
            "AnonymousAuthenticationFilter",
            "SessionManagementFilter",
            "ExceptionTranslationFilter",
            "AuthorizationFilter",
        )
        assertThat(app.filters.map { it.javaClass.simpleName }).containsExactly(
            "DisableEncodeUrlFilter",
            "WebAsyncManagerIntegrationFilter",
            "SecurityContextHolderFilter",
            "HeaderWriterFilter",
            "CorsFilter",
            "LogoutFilter",
            "OAuth2ProtectedResourceMetadataFilter",
            "InternalSecretAuthenticationFilter",
            "OptionalJwtAuthenticationFilter",
            "BearerTokenAuthenticationFilter",
            "DeletedAccountRejectionFilter",
            "AuthenticationFilter",
            "RequestCacheAwareFilter",
            "SecurityContextHolderAwareRequestFilter",
            "AnonymousAuthenticationFilter",
            "SessionManagementFilter",
            "ExceptionTranslationFilter",
            "AuthorizationFilter",
        )
    }

    @Test
    fun `서블릿 필터로 등록되는 것은 현재 목록뿐이고 보안 필터는 보안 체인 하나로만 등록된다`() {
        // Filter 구현을 빈으로 만들면 Boot가 서블릿 필터로도 자동 등록해 보안 체인 밖에서 모든 경로에 한 번 더 돈다.
        // 보안 필터를 모듈로 옮기면서 빈으로 노출하는 회귀를 잡으려고 등록 목록을 현재 값으로 고정한다.
        // Filter 빈뿐 아니라 FilterRegistrationBean(Sentry, MVC 관측)까지 Boot가 실제로 등록하는 목록 전체다.
        val registered = ServletContextInitializerBeans(beanFactory)
            .filterIsInstance<AbstractFilterRegistrationBean<*>>()
            .filter { it.isEnabled }
            .map { it.filterName }
        assertThat(registered).containsExactlyInAnyOrder(
            "sentrySpringFilter",
            "requestCorrelationFilter",
            "characterEncodingFilter",
            "sentryTracingFilter",
            "webMvcObservationFilter",
            "apiRequestLoggingFilter",
            "formContentFilter",
            "requestContextFilter",
            "springSecurityFilterChain",
            "sentryUserFilter",
        )
    }

    @Test
    fun `토큰 없음과 무효 토큰의 필수 경로 401은 공통 본문과 Bearer 챌린지 헤더를 낸다`() {
        for (token in listOf(null, FORGED_TOKEN, expiredToken())) {
            val label = if (token == null) "토큰 없음" else if (token == FORGED_TOKEN) "위조" else "만료"
            val result = exchange(HttpMethod.GET, "/api/v1/users/me/credits", token)
            assertErrorBody(result, 401, "UNAUTHORIZED", "유효하지 않은 인증입니다.", "/api/v1/users/me/credits")
            assertThat(result.responseHeaders.getFirst(HttpHeaders.WWW_AUTHENTICATE)).describedAs(label).isEqualTo("Bearer")
        }
    }

    @Test
    fun `서명은 맞고 만료된 토큰은 선택 인증 경로에서 익명으로 통과한다`() {
        val result = exchange(HttpMethod.GET, "/api/v1/stories", expiredToken())
        assertThat(result.status.value()).isEqualTo(200)
    }

    @Test
    fun `관리자 체인의 인증 실패는 공통 401 본문과 챌린지 헤더이고 자격 없음은 공통 403 본문이다`() {
        val path = "/api/v1/admin/members"
        val member = users.save(User(nickname = "역할 없는 회원"))
        for ((label, token) in listOf("토큰 없음" to null, "앱 토큰" to tokens.issueAccessToken(member.publicId), "위조" to FORGED_TOKEN)) {
            val result = exchange(HttpMethod.GET, path, token)
            assertErrorBody(result, 401, "UNAUTHORIZED", "유효하지 않은 인증입니다.", path)
            assertThat(result.responseHeaders.getFirst(HttpHeaders.WWW_AUTHENTICATE)).describedAs(label).isEqualTo("Bearer")
        }
        val forbidden = exchange(HttpMethod.GET, path, tokens.issueAdminToken(member.publicId))
        assertErrorBody(forbidden, 403, "FORBIDDEN", "관리자 권한이 없습니다.", path)
        assertThat(forbidden.responseHeaders.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull()
    }

    @Test
    fun `탈퇴 계정의 유효 토큰은 필수 경로와 선택 인증 경로 모두 같은 본문의 401이고 챌린지 헤더는 없다`() {
        val deleted = users.save(User(nickname = "탈퇴 회원", status = UserStatus.DELETED))
        val token = tokens.issueAccessToken(deleted.publicId)
        for (path in listOf("/api/v1/users/me/credits", "/api/v1/stories")) {
            val result = exchange(HttpMethod.GET, path, token)
            assertErrorBody(result, 401, "UNAUTHORIZED", "유효하지 않은 인증입니다.", path)
            assertThat(result.responseHeaders.getFirst(HttpHeaders.WWW_AUTHENTICATE)).describedAs(path).isNull()
        }
    }

    /**
     * 인가 단계의 permitAll과 리소스 서버의 Bearer 해석 생략만 본다. 판정은 엔트리 포인트의 거절(401과 Bearer 챌린지)이다.
     * 내부 경로의 공유 시크릿 거절은 챌린지 헤더 없이 401을 쓰므로 여기서는 보이지 않고,
     * InternalPushEligibilityControllerIntegrationTests 등 내부 API 테스트가 덮는다.
     */
    @Test
    fun `공개 경로는 토큰 없이 인가를 통과하고 Bearer 해석 생략 경로는 무효 토큰도 통과한다`() {
        val soft = SoftAssertions()
        for (row in PUBLIC_ROUTES) {
            val anonymous = exchange(row.method, row.path, token = null, headers = row.headers)
            soft.assertThat(isSecurityRejection(anonymous)).describedAs("토큰 없음 ${row.method} ${row.path} -> ${anonymous.status}").isFalse()
            val forged = exchange(row.method, row.path, FORGED_TOKEN, row.headers)
            soft.assertThat(isSecurityRejection(forged))
                .describedAs("무효 토큰 ${row.method} ${row.path} -> ${forged.status}")
                .isEqualTo(!row.skipsBearer)
        }
        soft.assertAll()
    }

    @Test
    fun `인증 필수 경로는 토큰 없이 401이다`() {
        val soft = SoftAssertions()
        for ((method, path) in AUTHENTICATED_ROUTES) {
            val result = exchange(method, path, token = null)
            soft.assertThat(isSecurityRejection(result)).describedAs("$method $path -> ${result.status}").isTrue()
        }
        soft.assertAll()
    }

    @Test
    fun `CORS preflight는 허용 출처에 PATCH를 포함한 메서드를 앱과 관리자 경로 모두 허용하고 다른 출처는 거절한다`() {
        for (path in listOf("/api/v1/stories/${UUID.randomUUID()}", "/api/v1/admin/members")) {
            val allowed = preflight(path, ALLOWED_ORIGIN)
            assertThat(allowed.status.value()).describedAs(path).isEqualTo(200)
            assertThat(allowed.responseHeaders.accessControlAllowOrigin).describedAs(path).isEqualTo(ALLOWED_ORIGIN)
            assertThat(allowed.responseHeaders.accessControlAllowMethods)
                .describedAs(path)
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS)
            assertThat(preflight(path, "http://evil.example").status.value()).describedAs(path).isEqualTo(403)
        }
    }

    private fun preflight(path: String, origin: String): EntityExchangeResult<String> =
        client.method(HttpMethod.OPTIONS).uri(path)
            .header(HttpHeaders.ORIGIN, origin)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
            .exchange().returnResult(String::class.java)

    private fun exchange(method: HttpMethod, path: String, token: String?, headers: Map<String, String> = emptyMap()): EntityExchangeResult<String> =
        client.method(method).uri(path)
            .apply { if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token") }
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .apply { if (method in BODY_METHODS) contentType(MediaType.APPLICATION_JSON).body("{}") }
            .exchange().returnResult(String::class.java)

    /** 보안 계층(엔트리 포인트)의 거절인지. 컨트롤러가 내는 401과 구분하려고 Bearer 챌린지 헤더를 본다. */
    private fun isSecurityRejection(result: EntityExchangeResult<String>): Boolean =
        result.status.value() == 401 && result.responseHeaders.getFirst(HttpHeaders.WWW_AUTHENTICATE) == "Bearer"

    private fun assertErrorBody(result: EntityExchangeResult<String>, status: Int, code: String, message: String, path: String) {
        assertThat(result.status.value()).describedAs(path).isEqualTo(status)
        assertThat(result.responseHeaders.contentType?.isCompatibleWith(MediaType.APPLICATION_JSON)).describedAs(path).isTrue()
        val body = mapper.readTree(result.responseBody)
        assertThat(body.propertyNames().toList()).describedAs(path).containsExactlyInAnyOrder("timestamp", "status", "code", "message", "path", "details")
        assertThat(body["status"].asInt()).isEqualTo(status)
        assertThat(body["code"].asText()).isEqualTo(code)
        assertThat(body["message"].asText()).isEqualTo(message)
        assertThat(body["path"].asText()).isEqualTo(path)
        assertThat(body["details"].isEmpty).isTrue()
    }

    /** 서명과 발급자는 맞고 만료 시각이 시계 오차(60초)를 넘겨 지난 앱 토큰. 검증기 단계에서 거절된다. */
    private fun expiredToken(): String =
        JwtTokenProvider(authProperties, Clock.fixed(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC))
            .issueAccessToken(users.save(User(nickname = "만료 토큰 회원")).publicId)

    private fun request(path: String) = MockHttpServletRequest("GET", path).apply { servletPath = path }

    private data class PublicRoute(
        val method: HttpMethod,
        val path: String,
        /** 리소스 서버가 토큰을 읽지 않는 경로(Bearer 해석 생략, 선택 인증, 공개 정적 자산, 내부 경로). */
        val skipsBearer: Boolean,
        val headers: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val FORGED_TOKEN = "forged.access.token"
        const val ALLOWED_ORIGIN = "http://localhost:3000"
        val BODY_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
        val ID: String = UUID.randomUUID().toString()

        private fun route(method: HttpMethod, path: String, skipsBearer: Boolean = true) = PublicRoute(method, path, skipsBearer)

        /** SecurityConfig 앱 체인의 permitAll 매처 전체. 경로 변수에는 임의 값을 넣는다. */
        val PUBLIC_ROUTES = listOf(
            // 토큰을 읽는 permitAll: 토큰 없으면 통과, 무효 토큰이면 리소스 서버가 401.
            route(HttpMethod.GET, "/actuator/health", skipsBearer = false),
            route(HttpMethod.GET, "/actuator/health/liveness", skipsBearer = false),
            route(HttpMethod.GET, "/v3/api-docs", skipsBearer = false),
            route(HttpMethod.GET, "/v3/api-docs/swagger-config", skipsBearer = false),
            route(HttpMethod.GET, "/swagger-ui/index.html", skipsBearer = false),
            route(HttpMethod.GET, "/swagger-ui.html", skipsBearer = false),
            route(HttpMethod.GET, "/error", skipsBearer = false),
            // 공개 정적 자산.
            route(HttpMethod.GET, "/profile-presets/missing.png"),
            // 선택 인증(OPTIONAL_AUTH_MATCHERS).
            route(HttpMethod.POST, "/api/v1/chats"),
            route(HttpMethod.POST, "/api/v1/chats/batch"),
            route(HttpMethod.GET, "/api/v1/chats/models"),
            route(HttpMethod.PATCH, "/api/v1/chats/$ID"),
            route(HttpMethod.GET, "/api/v1/chats/$ID"),
            route(HttpMethod.GET, "/api/v1/chats/$ID/generations/$ID/stream"),
            route(HttpMethod.DELETE, "/api/v1/chats/$ID"),
            route(HttpMethod.POST, "/api/v1/chats/$ID/shares"),
            route(HttpMethod.GET, "/api/v1/shares/$ID"),
            route(HttpMethod.POST, "/api/v1/chats/$ID/turns/stream"),
            route(HttpMethod.POST, "/api/v1/chats/$ID/turns/regenerate/stream"),
            route(HttpMethod.POST, "/api/v1/chats/$ID/turns/edit/stream"),
            route(HttpMethod.POST, "/api/v1/chats/$ID/turns/1/choices"),
            route(HttpMethod.GET, "/api/v1/stories/lorebooks"),
            route(HttpMethod.GET, "/api/v1/stories"),
            route(HttpMethod.GET, "/api/v1/stories/search"),
            route(HttpMethod.GET, "/api/v1/stories/explore"),
            route(HttpMethod.GET, "/api/v1/stories/$ID"),
            route(HttpMethod.DELETE, "/api/v1/stories/$ID"),
            route(HttpMethod.GET, "/api/v1/stories/$ID/edit"),
            route(HttpMethod.GET, "/api/v1/stories/art-styles"),
            route(HttpMethod.GET, "/api/v1/stories/simple/tags"),
            route(HttpMethod.POST, "/api/v1/stories/simple/storylines"),
            route(HttpMethod.POST, "/api/v1/stories/simple"),
            route(HttpMethod.PUT, "/api/v1/stories/simple/storylines/1/rating"),
            route(HttpMethod.DELETE, "/api/v1/stories/simple/storylines/1/rating"),
            route(HttpMethod.GET, "/api/v1/stories/simple/creation-requests/$ID"),
            route(HttpMethod.POST, "/api/v1/stories/batch"),
            route(HttpMethod.POST, "/api/v1/feedbacks"),
            route(HttpMethod.GET, "/api/v1/users/me/trials"),
            route(HttpMethod.GET, "/api/v1/credits/policies"),
            route(HttpMethod.GET, "/api/v1/credits/products"),
            // 공개 인증과 Bearer 해석 생략(BEARER_SKIP_MATCHERS).
            route(HttpMethod.GET, "/api/v1/stories/home"),
            route(HttpMethod.GET, "/api/v1/notices"),
            route(HttpMethod.GET, "/api/v1/notices/1"),
            route(HttpMethod.POST, "/api/v1/auth/social/google"),
            route(HttpMethod.GET, "/api/v1/guests/consents"),
            route(HttpMethod.POST, "/api/v1/guests/consents"),
            route(HttpMethod.POST, "/api/v1/webhooks/groble"),
            route(HttpMethod.POST, "/api/v1/auth/login/google"),
            route(HttpMethod.POST, "/api/v1/auth/login/kakao"),
            route(HttpMethod.POST, "/api/v1/auth/token/refresh"),
            route(HttpMethod.POST, "/api/v1/auth/logout"),
            // 내부 경로는 사용자 인증 대신 공유 시크릿을 본다.
            PublicRoute(
                HttpMethod.GET, "/internal/users/$ID/push-eligibility", skipsBearer = true,
                headers = mapOf("X-Manyak-Internal-Secret" to IntegrationTest.INTERNAL_SHARED_SECRET),
            ),
        )

        /** 명시적 authenticated 매처와 anyRequest의 대표 경로. 앞의 permitAll 매처보다 먼저 맞아야 하는 경로를 포함한다. */
        val AUTHENTICATED_ROUTES = listOf(
            HttpMethod.GET to "/api/v1/stories/submissions",
            HttpMethod.GET to "/api/v1/stories/submissions/$ID",
            HttpMethod.POST to "/api/v1/chats/$ID/fork",
            HttpMethod.PATCH to "/api/v1/stories/$ID",
            HttpMethod.GET to "/api/v1/users/me/credits",
            HttpMethod.GET to "/api/v1/auth/me",
            HttpMethod.GET to "/api/v1/profile-presets",
            HttpMethod.GET to "/api/v1/not-mapped",
            // 지표 무인증 허용은 local 프로파일만이다(SecurityConfig 주석). 테스트 프로파일에서는 인증이 필요해야 한다.
            HttpMethod.GET to "/actuator/prometheus",
        )
    }
}
