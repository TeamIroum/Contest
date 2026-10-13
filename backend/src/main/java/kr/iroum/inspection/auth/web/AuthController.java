package kr.iroum.inspection.auth.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.auth.password.AdminPasswordService;
import kr.iroum.inspection.auth.session.AuthenticatedUserToken;
import kr.iroum.inspection.auth.session.IssuedTokens;
import kr.iroum.inspection.auth.session.SessionService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final SessionService sessions;
    private final AuthCookies cookies;
    private final AdminPasswordService passwords;

    public AuthController(SessionService sessions, AuthCookies cookies, AdminPasswordService passwords) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.passwords = passwords;
    }

    /** CSRF 토큰 발급. 같은 값이 HttpOnly 쿠키로도 내려가며 변경 요청은 X-CSRF-Token으로 보낸다. */
    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("csrfToken", token.getToken());
    }

    @PostMapping("/token/refresh")
    TokenResponse refresh(@CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken,
            HttpServletResponse response) {
        IssuedTokens tokens = sessions.refresh(refreshToken);
        cookies.setRefresh(response, tokens.refreshToken(), tokens.refreshTokenExpiresAt());
        return TokenResponse.of(tokens);
    }

    /**
     * 현재 세션 종료. Bearer가 있으면 그 세션, 없으면 Refresh 쿠키의 세션을 끝낸다.
     * 비밀번호 변경 제한 세션 쿠키가 있으면 그것도 소진하고(D-23) 인증 쿠키를 모두 지운다.
     */
    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(Authentication authentication, @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken,
            @CookieValue(name = AuthCookies.RESTRICTED, required = false) String restrictedToken, HttpServletResponse response) {
        if (authentication instanceof AuthenticatedUserToken token) {
            sessions.revoke(token.getPrincipal().sessionId());
        } else {
            sessions.revokeByRefreshToken(refreshToken);
        }
        passwords.endRestricted(restrictedToken);
        cookies.clearAll(response);
    }
}
