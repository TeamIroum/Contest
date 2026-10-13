package kr.iroum.inspection.common.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import kr.iroum.inspection.auth.AuthProperties;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse;
import kr.iroum.inspection.common.error.ErrorResponses;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증: Authorization Bearer(Access JWT) + 요청마다 세션·계정 상태 확인. 쿠키를 쓰는 인증 경로와 기기 로그아웃은 CSRF(X-CSRF-Token) 검증.
 * 로그인 전 경로만 공개하며 임의 인증 우회·고정 로그인은 두지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    public static final String CSRF_COOKIE = "__Host-itda-csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper, AuthProperties properties,
            SessionJwtAuthenticationConverter converter) throws Exception {
        CookieCsrfTokenRepository csrfRepository = new CookieCsrfTokenRepository();
        csrfRepository.setCookieName(CSRF_COOKIE);
        csrfRepository.setHeaderName(CSRF_HEADER);
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(properties.cookieSecure()).sameSite("Strict"));

        http
                .cors(cors -> {
                })
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Bearer 인증이 될 때마다 CSRF 쿠키를 지우지 않게 한다. 지우면 로그인 뒤 GET 한 번에 쿠키가 비어 다음 변경 요청이 403이 된다.
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                        .requireCsrfProtectionMatcher(csrfProtected()))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/api/v1/auth/csrf").permitAll()
                        // 로그인 전·쿠키 기반 인증 경로. 각 컨트롤러가 자격을 직접 확인한다.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token/refresh", "/api/v1/auth/sms-challenges",
                                "/api/v1/auth/sms-verifications", "/api/v1/auth/demo/otp-requests", "/api/v1/auth/demo/otp-verifications",
                                "/api/v1/auth/admin/sessions", "/api/v1/auth/password-changes").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/session").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, ex) -> unauthenticated(response, jsonMapper, ex)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> unauthenticated(response, jsonMapper, ex))
                        .accessDeniedHandler((request, response, ex) -> write(response, jsonMapper,
                                ErrorResponses.of(ErrorCode.FORBIDDEN, "이 작업을 할 권한이 없습니다."))));
        http.addFilterBefore(new BearerCsrfFilter(jsonMapper), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** OpenAPI는 업무 API의 모든 변경 요청(POST·PUT·PATCH·DELETE)에 X-CSRF-Token을 요구한다. */
    static RequestMatcher csrfProtected() {
        return (HttpServletRequest request) -> {
            String method = request.getMethod();
            if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
                return false;
            }
            return request.getRequestURI().startsWith("/api/v1/");
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", CSRF_HEADER, "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void unauthenticated(HttpServletResponse response, JsonMapper mapper, AuthenticationException ex)
            throws IOException {
        if (ex instanceof AuthenticationServiceException) {
            // Redis·DB에 닿지 못하면 보호 API를 허용하지 않고 일시 장애로 응답한다.
            write(response, mapper, ErrorResponses.of(ErrorCode.SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."));
            return;
        }
        write(response, mapper, ErrorResponses.of(ErrorCode.AUTH_REQUIRED, "다시 로그인해 주세요."));
    }

    private static void write(HttpServletResponse response, JsonMapper mapper, ErrorResponse body) throws IOException {
        response.setStatus(ErrorCode.valueOf(body.code()).status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
