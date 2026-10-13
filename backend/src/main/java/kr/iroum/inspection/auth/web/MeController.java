package kr.iroum.inspection.auth.web;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.auth.account.AccountRepository;
import kr.iroum.inspection.auth.account.AccountRepository.Account;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.auth.session.LoginSession;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final AccountRepository accounts;
    private final SessionService sessions;
    private final AuthCookies cookies;

    public MeController(AccountRepository accounts, SessionService sessions, AuthCookies cookies) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.cookies = cookies;
    }

    public record Me(UUID id, String name, List<String> roles, String accountStatus, boolean passwordChangeRequired,
            UUID participantId, List<AccountRepository.Scope> scopes) {
    }

    public record SessionItem(UUID id, String deviceLabel, Instant createdAt, Instant lastUsedAt, boolean current) {
    }

    @GetMapping
    Me me(@AuthenticationPrincipal AuthenticatedUser user) {
        Account account = accounts.find(user.userId()).orElseThrow();
        String status = account.lockedUntil() != null && account.lockedUntil().isAfter(Instant.now()) ? "LOCKED" : "ACTIVE";
        return new Me(account.id(), account.name(), List.copyOf(user.roles()), status, account.passwordChangeRequired(),
                accounts.activeParticipantId(user.userId()).orElse(null), accounts.scopes(user.userId()));
    }

    @GetMapping("/sessions")
    Map<String, List<SessionItem>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        List<SessionItem> items = sessions.sessions(user.userId()).stream()
                .sorted(Comparator.comparing(LoginSession::issuedAt))
                .map(s -> new SessionItem(s.id(), s.deviceLabel(), s.issuedAt(), s.lastUsedAt(), s.id().equals(user.sessionId())))
                .toList();
        return Map.of("items", items);
    }

    @DeleteMapping("/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeAll(@AuthenticationPrincipal AuthenticatedUser user, HttpServletResponse response) {
        sessions.revokeAll(user.userId());
        cookies.clearAll(response);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sessionId, HttpServletResponse response) {
        boolean owned = sessions.sessions(user.userId()).stream().anyMatch(s -> s.id().equals(sessionId));
        if (!owned) {
            throw new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
        }
        sessions.revoke(sessionId);
        if (sessionId.equals(user.sessionId())) {
            cookies.clearAll(response);
        }
    }
}
