package kr.iroum.inspection.patrol.web;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.patrol.command.AttendanceService;
import kr.iroum.inspection.patrol.command.CorrectionCancellationService;
import kr.iroum.inspection.patrol.command.ReturnService;
import kr.iroum.inspection.patrol.command.StartDepartureService;
import kr.iroum.inspection.patrol.PatrolQueryService;
import kr.iroum.inspection.patrol.PatrolViews.AdminPatrol;
import kr.iroum.inspection.patrol.PatrolViews.AdminPatrolItem;
import kr.iroum.inspection.patrol.PatrolViews.Page;

/** 활동 담당자 순회 조회(업무 API 8절). */
@RestController
@RequestMapping("/api/v1/admin/patrols")
public class AdminPatrolController {

    private final PatrolQueryService queries;
    private final AttendanceService attendance;
    private final StartDepartureService starts;
    private final ReturnService returns;
    private final CorrectionCancellationService corrections;
    private final kr.iroum.inspection.patrol.command.TransferService transfers;
    private final tools.jackson.databind.json.JsonMapper json;

    public AdminPatrolController(PatrolQueryService queries, AttendanceService attendance, StartDepartureService starts,
            ReturnService returns, CorrectionCancellationService corrections, kr.iroum.inspection.patrol.command.TransferService transfers,
            tools.jackson.databind.json.JsonMapper json) {
        this.queries = queries;
        this.attendance = attendance;
        this.starts = starts;
        this.returns = returns;
        this.corrections = corrections;
        this.transfers = transfers;
        this.json = json;
    }

    /** 활동 중 조 이동(D-34). sourceVersion은 이 순회, targetVersion은 합류할 순회의 최신 version이다. */
    public record TransferRequest(@NotNull UUID targetPatrolId, @NotNull @PositiveOrZero Long sourceVersion,
            @NotNull @PositiveOrZero Long targetVersion, @NotBlank @Size(max = 500) String reason) {
    }

    @GetMapping("/{patrolId}/members/{participantId}/transfer-candidates")
    kr.iroum.inspection.patrol.command.TransferService.Candidates transferCandidates(Authentication auth, @PathVariable UUID patrolId,
            @PathVariable UUID participantId) {
        return transfers.candidates(CurrentUser.of(auth), patrolId, participantId);
    }

    @PostMapping("/{patrolId}/members/{participantId}/transfers")
    ResponseEntity<kr.iroum.inspection.patrol.command.TransferService.TransferResult> transfer(Authentication auth,
            @PathVariable UUID patrolId, @PathVariable UUID participantId, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody TransferRequest body) {
        var outcome = transfers.transfer(CurrentUser.of(auth), patrolId, participantId, body.targetPatrolId(), body.sourceVersion(),
                body.targetVersion(), body.reason(), key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    public record Cancellation(@NotBlank @Size(max = 500) String reason, @NotNull @PositiveOrZero Long version) {
    }

    /**
     * 시각 정정 본문(TimeCorrection): startedAt·returnedAt 중 하나 이상, 생략은 유지, 명시적 null은 거절, reason 1~500자, version.
     * 생략과 null을 구분하려고 JSON 트리로 읽는다.
     */
    @PostMapping("/{patrolId}/members/{participantId}/time-corrections")
    ResponseEntity<CorrectionCancellationService.TimeCorrectionResult> correctTime(Authentication auth, @PathVariable UUID patrolId,
            @PathVariable UUID participantId, @RequestHeader("Idempotency-Key") UUID key, @RequestBody String raw) {
        tools.jackson.databind.JsonNode body;
        try {
            body = json.readTree(raw);
        } catch (RuntimeException e) {
            throw invalid("body", "INVALID_FORMAT");
        }
        if (body == null || !body.isObject()) {
            throw invalid("body", "INVALID_FORMAT");
        }
        java.util.Set<String> allowed = java.util.Set.of("startedAt", "returnedAt", "reason", "version");
        for (String name : body.propertyNames()) {
            if (!allowed.contains(name)) {
                throw invalid(name, "INVALID_FORMAT");
            }
        }
        java.time.Instant startedAt = instant(body, "startedAt");
        java.time.Instant returnedAt = instant(body, "returnedAt");
        if (startedAt == null && returnedAt == null) {
            throw invalid("startedAt", "REQUIRED");
        }
        tools.jackson.databind.JsonNode reason = body.get("reason");
        if (reason == null || !reason.isString() || reason.asString().isBlank()) {
            throw invalid("reason", "REQUIRED");
        }
        if (reason.asString().length() > 500) {
            throw invalid("reason", "TOO_LONG");
        }
        tools.jackson.databind.JsonNode version = body.get("version");
        if (version == null || !version.isIntegralNumber() || version.asLong() < 0) {
            throw invalid("version", version == null ? "REQUIRED" : "INVALID_FORMAT");
        }
        var outcome = corrections.correct(CurrentUser.of(auth), patrolId, participantId, startedAt, returnedAt, reason.asString(),
                version.asLong(), key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/{patrolId}/cancellations")
    ResponseEntity<CorrectionCancellationService.CancellationResult> cancel(Authentication auth, @PathVariable UUID patrolId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Cancellation body) {
        var outcome = corrections.cancel(CurrentUser.of(auth), patrolId, body.reason(), body.version(), key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    private static java.time.Instant instant(tools.jackson.databind.JsonNode body, String field) {
        if (!body.has(field)) {
            return null;
        }
        tools.jackson.databind.JsonNode node = body.get(field);
        if (node.isNull() || !node.isString()) {
            throw invalid(field, "INVALID_FORMAT");
        }
        try {
            return java.time.OffsetDateTime.parse(node.asString()).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw invalid(field, "INVALID_FORMAT");
        }
    }

    private static kr.iroum.inspection.common.error.ApiException invalid(String field, String reason) {
        return new kr.iroum.inspection.common.error.ApiException(kr.iroum.inspection.common.error.ErrorCode.VALIDATION_FAILED,
                "입력 내용을 확인해 주세요.", null, java.util.List.of(new kr.iroum.inspection.common.error.ErrorResponse.FieldError(field,
                        reason, "입력 형식을 확인해 주세요.")));
    }

    public record ReturnConfirmation(@NotNull UUID participantId, @NotNull java.time.Instant returnedAt,
            @NotNull SeniorPatrolController.ReturnReason returnReason, SeniorPatrolController.AbortReason abortReason,
            @NotNull @PositiveOrZero Long version) {
    }

    @PostMapping("/{patrolId}/return-confirmations")
    ResponseEntity<ReturnService.ReturnConfirmationResult> confirmReturn(Authentication auth, @PathVariable UUID patrolId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody ReturnConfirmation body) {
        var outcome = returns.confirm(CurrentUser.of(auth), patrolId, body.participantId(), body.returnedAt(), body.returnReason().name(),
                body.abortReason() == null ? null : body.abortReason().name(), body.version(), key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    public record VersionOnly(@NotNull @PositiveOrZero Long version) {
    }

    @PostMapping("/{patrolId}/departure-confirmations")
    ResponseEntity<StartDepartureService.DepartureResult> confirmDeparture(Authentication auth, @PathVariable UUID patrolId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody VersionOnly body) {
        var outcome = starts.confirmDeparture(CurrentUser.of(auth), patrolId, body.version(), key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    public record AttendanceUpdate(@NotNull Attendance attendance, @Size(max = 500) String proxyReason,
            @Size(max = 500) String correctionReason, @NotNull @PositiveOrZero Long version) {
    }

    enum Attendance {
        PRESENT, ABSENT
    }

    @PutMapping("/{patrolId}/attendance/{participantId}")
    AttendanceService.AttendanceUpdateResult updateAttendance(Authentication auth, @PathVariable UUID patrolId,
            @PathVariable UUID participantId, @Valid @RequestBody AttendanceUpdate body) {
        return attendance.update(CurrentUser.of(auth), patrolId, participantId, body.attendance().name(), body.proxyReason(),
                body.correctionReason(), body.version());
    }

    @GetMapping
    Page<AdminPatrolItem> list(Authentication auth,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) ActivityStatus activityStatus, @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return queries.adminList(CurrentUser.of(auth), date, activityStatus == null ? null : activityStatus.name(), organizationId, page,
                size);
    }

    @GetMapping("/{patrolId}")
    AdminPatrol detail(Authentication auth, @PathVariable UUID patrolId) {
        return queries.adminPatrol(CurrentUser.of(auth), patrolId);
    }

    enum ActivityStatus {
        ASSIGNED, IN_PROGRESS, RETURNING, ENDED, CANCELLED
    }
}
