package kr.iroum.inspection.participant;

import java.util.List;
import java.util.UUID;

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
import kr.iroum.inspection.participant.ParticipantService.Participant;
import kr.iroum.inspection.patrol.PatrolViews.Page;

/** 활동 담당자 참여자 계정 관리(기능정의서 ACT-01). service/: 원본에 없던 API다. */
@RestController
@RequestMapping("/api/v1/admin/participants")
public class AdminParticipantController {

    private final ParticipantService participants;

    public AdminParticipantController(ParticipantService participants) {
        this.participants = participants;
    }

    public record NewParticipant(@NotNull UUID organizationId, @NotBlank @Size(max = 50) String name, @NotBlank @Size(max = 20) String phoneNumber,
            @NotNull @Size(min = 1, max = 7) List<String> availableDays) {
    }

    public record ParticipantChange(@Size(max = 50) String name, @Size(min = 1, max = 7) List<String> availableDays,
            @NotNull @PositiveOrZero Long version) {
    }

    public record StatusChange(@NotBlank String status, @NotBlank @Size(max = 500) String reason, @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    Page<Participant> list(Authentication auth, @RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return participants.list(CurrentUser.of(auth), status, q, page, size);
    }

    @PostMapping
    ResponseEntity<Participant> create(Authentication auth, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody NewParticipant body) {
        var outcome = participants.create(CurrentUser.of(auth), body.organizationId(), body.name(), body.phoneNumber(), body.availableDays(), key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @GetMapping("/organizations")
    List<ParticipantService.Organization> organizations(Authentication auth) {
        return participants.organizations(CurrentUser.of(auth));
    }

    @GetMapping("/{participantId}")
    Participant get(Authentication auth, @PathVariable UUID participantId) {
        return participants.get(CurrentUser.of(auth), participantId);
    }

    @PatchMapping("/{participantId}")
    Participant update(Authentication auth, @PathVariable UUID participantId, @Valid @RequestBody ParticipantChange body) {
        return participants.update(CurrentUser.of(auth), participantId, body.name(), body.availableDays(), body.version());
    }

    @PostMapping("/{participantId}/status-changes")
    ResponseEntity<Participant> changeStatus(Authentication auth, @PathVariable UUID participantId, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody StatusChange body) {
        var outcome = participants.changeStatus(CurrentUser.of(auth), participantId, body.status(), body.reason(), body.version(), key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
