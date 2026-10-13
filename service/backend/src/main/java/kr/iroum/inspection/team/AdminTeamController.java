package kr.iroum.inspection.team;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.team.TeamService.Team;

/** 활동 담당자 평소 조 편성(기능정의서 ACT-02). service/: 원본에 없던 API다. */
@RestController
@RequestMapping("/api/v1/admin/teams")
public class AdminTeamController {

    private final TeamService teams;

    public AdminTeamController(TeamService teams) {
        this.teams = teams;
    }

    public record NewTeam(@NotNull UUID organizationId, @NotBlank @Size(max = 50) String name, @NotNull @Size(min = 2, max = 3) List<UUID> participantIds,
            LocalDate effectiveFrom) {
    }

    public record TeamChange(@Size(max = 50) String name, @Size(min = 2, max = 3) List<UUID> participantIds, LocalDate effectiveFrom,
            @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    List<Team> list(Authentication auth, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return teams.list(CurrentUser.of(auth), date);
    }

    @PostMapping
    ResponseEntity<Team> create(Authentication auth, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody NewTeam body) {
        var outcome = teams.create(CurrentUser.of(auth), body.organizationId(), body.name(), body.participantIds(), body.effectiveFrom(), key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PatchMapping("/{teamId}")
    Team update(Authentication auth, @PathVariable UUID teamId, @Valid @RequestBody TeamChange body) {
        return teams.update(CurrentUser.of(auth), teamId, body.name(), body.participantIds(), body.effectiveFrom(), body.version());
    }
}
