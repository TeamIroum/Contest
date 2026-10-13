package kr.iroum.inspection.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.schedule.PatrolAssignmentService.ScheduledPatrol;

/** 활동 담당자 날짜별 순회 배정(기능정의서 ACT-04)·순회 일정과 재점검 구도·후보(ACT-10). service/: 원본에 없던 API다. */
@RestController
public class AdminPatrolAssignmentController {

    private final PatrolAssignmentService assignments;

    private final ReinspectionSlotService slots;

    public AdminPatrolAssignmentController(PatrolAssignmentService assignments, ReinspectionSlotService slots) {
        this.assignments = assignments;
        this.slots = slots;
    }

    public record NewSlot(@NotBlank String purpose, @NotBlank @Size(max = 50) String label, @Size(max = 500) String instruction, Boolean required,
            @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping("/api/v1/admin/reinspection-requests/{requestId}/candidates")
    List<ReinspectionSlotService.Candidate> candidates(Authentication auth, @PathVariable UUID requestId) {
        return slots.candidates(CurrentUser.of(auth), requestId);
    }

    @PostMapping("/api/v1/admin/patrols/{patrolId}/stops/{stopId}/capture-slots")
    ResponseEntity<ReinspectionSlotService.SlotResult> addSlot(Authentication auth, @PathVariable UUID patrolId, @PathVariable UUID stopId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody NewSlot body) {
        var outcome = slots.addSlot(CurrentUser.of(auth), patrolId, stopId, body.purpose(), body.label(), body.instruction(),
                body.required() == null || body.required(), body.version(), key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    public record NewPatrol(@NotNull LocalDate activityDate, @NotNull UUID teamId, @NotNull UUID routeVersionId, @NotNull Instant scheduledStartAt,
            @NotNull Instant expectedReturnAt) {
    }

    @GetMapping("/api/v1/admin/patrol-schedule")
    List<ScheduledPatrol> schedule(Authentication auth, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return assignments.schedule(CurrentUser.of(auth), from, to);
    }

    @PostMapping("/api/v1/admin/patrols")
    ResponseEntity<ScheduledPatrol> assign(Authentication auth, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody NewPatrol body) {
        var outcome = assignments.assign(CurrentUser.of(auth), body.activityDate(), body.teamId(), body.routeVersionId(), body.scheduledStartAt(),
                body.expectedReturnAt(), key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
