package kr.iroum.inspection.auth.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import kr.iroum.inspection.auth.AuthProperties;

/** 인증 쿠키. 값은 JSON·로그에 남기지 않는다(업무 API 4.1절). */
@Component
public class AuthCookies {

    public static final String REFRESH = "__Host-itda-refresh";
    public static final String RESTRICTED = "__Host-password-change";

    private final AuthProperties properties;
    private final Clock clock;

    public AuthCookies(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Refresh 쿠키. Max-Age는 세션 절대 만료까지 남은 초이며 갱신으로 늘지 않는다. */
    public void setRefresh(HttpServletResponse response, String token, Instant absoluteExpiresAt) {
        long seconds = Math.max(0, Duration.between(clock.instant(), absoluteExpiresAt).getSeconds());
        add(response, ResponseCookie.from(REFRESH, token).sameSite("Lax").maxAge(seconds));
    }

    /** 비밀번호 변경 제한 세션 쿠키(D-23). 막 발급한 세션이므로 Max-Age는 발급 기간(600초) 그대로다. */
    public void setRestricted(HttpServletResponse response, String token) {
        add(response, ResponseCookie.from(RESTRICTED, token).sameSite("Strict").maxAge(properties.restrictedSessionTtl()));
    }

    public void clearAll(HttpServletResponse response) {
        add(response, ResponseCookie.from(REFRESH, "").sameSite("Lax").maxAge(0));
        add(response, ResponseCookie.from(RESTRICTED, "").sameSite("Strict").maxAge(0));
    }

    private void add(HttpServletResponse response, ResponseCookie.ResponseCookieBuilder builder) {
        ResponseCookie cookie = builder.httpOnly(true).secure(properties.cookieSecure()).path("/").build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
