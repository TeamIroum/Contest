package kr.iroum.inspection.auth.password;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.auth.password.AdminPasswordService.ChangeResult;
import kr.iroum.inspection.auth.password.AdminPasswordService.LoginResult;
import kr.iroum.inspection.auth.session.AuthenticatedUserToken;
import kr.iroum.inspection.auth.session.IssuedTokens;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.auth.web.AuthCookies;
import kr.iroum.inspection.auth.web.TokenResponse;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/**
 * 관리자 로그인과 비밀번호 변경(업무 API 4.3절, D-16·D-21~23). MFA는 범위 밖이다(D-21).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AdminAuthController {

    public record AdminLogin(@NotBlank String loginId, @NotEmpty String password, @NotBlank String clientId,
            @NotBlank String deviceLabel) {
    }

    public record PasswordChange(@NotEmpty String currentPassword, @NotNull String newPassword) {
    }

    /** 임시 비밀번호 로그인 응답(D-23). 제한 세션 값은 쿠키로만 보낸다. */
    public record PasswordChangeRequired(boolean passwordChangeRequired, Instant restrictedSessionExpiresAt) {
    }

    private final AdminPasswordService passwords;
    private final SessionService sessions;
    private final AuthCookies cookies;

    public AdminAuthController(AdminPasswordService passwords, SessionService sessions, AuthCookies cookies) {
        this.passwords = passwords;
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @PostMapping("/admin/sessions")
    Object login(@Valid @RequestBody AdminLogin body, HttpServletResponse response) {
        LoginResult result = passwords.login(body.loginId(), body.password());
        return switch (result) {
            case LoginResult.Success success -> {
                IssuedTokens tokens = sessions.issue(success.userId(), success.authVersion(), body.clientId(), body.deviceLabel());
                cookies.setRefresh(response, tokens.refreshToken(), tokens.refreshTokenExpiresAt());
                yield TokenResponse.of(tokens);
            }
            case LoginResult.ChangeRequired restricted -> {
                cookies.setRestricted(response, restricted.restrictedToken());
                yield new PasswordChangeRequired(true, restricted.expiresAt());
            }
            case LoginResult.Locked locked -> throw AdminPasswordService.rateLimited(locked.retryAfter());
            case LoginResult.Failed failed -> throw AdminPasswordService.loginFailed();
        };
    }

    /**
     * 제한 세션 쿠키 또는 본인 Bearer로 비밀번호를 바꾼다. 성공하면 모든 기기 세션을 끝내고 쿠키를 지운다(자동 로그인 없음).
     * Redis 세션 정리는 후속 처리이며, 그 전에도 auth_version 증가로 이전 Access·Refresh는 즉시 거부된다.
     */
    @PostMapping("/password-changes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void change(@Valid @RequestBody PasswordChange body, Authentication authentication,
            @CookieValue(name = AuthCookies.RESTRICTED, required = false) String restrictedToken, HttpServletResponse response) {
        UUID bearerUser = authentication instanceof AuthenticatedUserToken token ? token.getPrincipal().userId() : null;
        if (bearerUser == null && restrictedToken == null) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, "다시 로그인해 주세요.");
        }
        if (!PasswordPolicy.satisfiedBy(body.newPassword())) {
            throw AdminPasswordService.invalidNewPassword("8자 이상, 영문·숫자·기호를 각각 1개 이상 넣어 주세요.");
        }
        ChangeResult result = passwords.change(restrictedToken, bearerUser, body.currentPassword(), body.newPassword());
        switch (result) {
            case ChangeResult.Changed changed -> {
                sessions.revokeAll(changed.userId());
                cookies.clearAll(response);
            }
            case ChangeResult.Rejected rejected -> throw rejected.error();
        }
    }
}
