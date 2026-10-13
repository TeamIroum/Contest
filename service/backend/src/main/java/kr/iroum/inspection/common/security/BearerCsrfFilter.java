package kr.iroum.inspection.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponses;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security는 Bearer 요청을 CSRF 검사에서 빼지만, OpenAPI는 기기 로그아웃·로그아웃·갱신에 X-CSRF-Token을 필수로 둔다.
 * 계약대로 Bearer가 붙은 같은 경로에도 쿠키(__Host-itda-csrf)와 헤더 값이 같아야 통과시킨다.
 */
class BearerCsrfFilter extends OncePerRequestFilter {

    private final JsonMapper mapper;

    BearerCsrfFilter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        boolean bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        if (bearer && SecurityConfig.csrfProtected().matches(request) && !tokenMatches(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getOutputStream(), ErrorResponses.of(ErrorCode.FORBIDDEN, "이 작업을 할 권한이 없습니다."));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean tokenMatches(HttpServletRequest request) {
        String header = request.getHeader(SecurityConfig.CSRF_HEADER);
        String cookie = null;
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (SecurityConfig.CSRF_COOKIE.equals(c.getName())) {
                    cookie = c.getValue();
                }
            }
        }
        return header != null && cookie != null && !header.isEmpty()
                && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), cookie.getBytes(StandardCharsets.UTF_8));
    }
}
