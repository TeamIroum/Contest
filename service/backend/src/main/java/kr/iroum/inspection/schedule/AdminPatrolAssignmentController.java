package kr.iroum.inspection.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.schedule.PatrolAssignmentService.ScheduledPatrol;

/** 활동 담당자 날짜별 순회 배정(기능정의서 ACT-04)과 순회 일정. service/: 원본에 없던 API다. */
@RestController
public class AdminPatrolAssignmentController {

    private final PatrolAssignmentService assignments;

    public AdminPatrolAssignmentController(PatrolAssignmentService assignments) {
        this.assignments = assignments;
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
