package kr.iroum.inspection.officer;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.officer.OfficerAccountService.Account;
import kr.iroum.inspection.officer.OfficerAccountService.Issued;
import kr.iroum.inspection.officer.OfficerAccountService.RoleInput;

/** 시스템 관리자 공무원 계정 관리(기능정의서 OPS-07·08). service/: 원본에 없던 API다. 임시 비밀번호 응답은 캐시하지 않는다. */
@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AdminOfficerAccountController {

    private final OfficerAccountService accounts;

    public AdminOfficerAccountController(OfficerAccountService accounts) {
        this.accounts = accounts;
    }

    public record NewAccount(@NotBlank @Size(max = 50) String name, @NotBlank @Size(max = 50) String loginId, @NotNull @Size(min = 1, max = 10) List<RoleInput> roles) {
    }

    public record RoleChange(@NotNull @Size(min = 1, max = 10) List<RoleInput> roles, @NotNull @PositiveOrZero Long version) {
    }

    public record Reason(@NotBlank @Size(max = 500) String reason) {
    }

    public record StatusChange(@NotBlank String status, @NotBlank @Size(max = 500) String reason, @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    List<Account> list(Authentication auth) {
        return accounts.list(CurrentUser.of(auth));
    }

    @GetMapping("/organizations")
    List<OfficerAccountService.Organization> organizations(Authentication auth) {
        return accounts.organizations(CurrentUser.of(auth));
    }

    @PostMapping
    ResponseEntity<Issued> issue(Authentication auth, @Valid @RequestBody NewAccount body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(accounts.issue(CurrentUser.of(auth), body.name(), body.loginId(), body.roles()));
    }

    @PutMapping("/{accountId}/roles")
    Account roles(Authentication auth, @PathVariable UUID accountId, @Valid @RequestBody RoleChange body) {
        return accounts.changeRoles(CurrentUser.of(auth), accountId, body.roles(), body.version());
    }

    @PostMapping("/{accountId}/password-resets")
    ResponseEntity<Issued> reset(Authentication auth, @PathVariable UUID accountId, @Valid @RequestBody Reason body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.resetPassword(CurrentUser.of(auth), accountId, body.reason()));
    }

    @PostMapping("/{accountId}/unlocks")
    Account unlock(Authentication auth, @PathVariable UUID accountId, @Valid @RequestBody Reason body) {
        return accounts.unlock(CurrentUser.of(auth), accountId, body.reason());
    }

    @PostMapping("/{accountId}/status-changes")
    Account status(Authentication auth, @PathVariable UUID accountId, @Valid @RequestBody StatusChange body) {
        return accounts.changeStatus(CurrentUser.of(auth), accountId, body.status(), body.reason(), body.version());
    }
}
