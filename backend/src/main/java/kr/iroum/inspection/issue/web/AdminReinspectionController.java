package kr.iroum.inspection.issue.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.issue.IssueViews.AssignmentResult;
import kr.iroum.inspection.issue.IssueViews.CancellationResult;
import kr.iroum.inspection.issue.IssueViews.ReinspectionRequestItem;
import kr.iroum.inspection.issue.ReinspectionService;
import kr.iroum.inspection.review.ReviewViews.Page;

/** 재점검 요청 목록·배정·배정 취소(업무 API 10절). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminReinspectionController {

    enum RequestStatus {
        OPEN, CLOSED
    }

    public record AssignmentBody(UUID captureSlotId, Long version, Long patrolVersion) {
    }

    public record CancellationBody(String reason, Long version, Long patrolVersion) {
    }

    private final ReinspectionService reinspections;

    public AdminReinspectionController(ReinspectionService reinspections) {
        this.reinspections = reinspections;
    }

    @GetMapping("/reinspection-requests")
    Page<ReinspectionRequestItem> list(Authentication auth, @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) UUID organizationId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return reinspections.list(CurrentUser.of(auth), status == null ? null : status.name(), organizationId, page, size);
    }

    @PostMapping("/reinspection-requests/{requestId}/assignments")
    ResponseEntity<AssignmentResult> assign(Authentication auth, @PathVariable UUID requestId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody AssignmentBody body) {
        IdempotencyService.Outcome<AssignmentResult> out = reinspections.assign(CurrentUser.of(auth), requestId, key, body.captureSlotId(),
                body.version(), body.patrolVersion());
        return ResponseEntity.status(out.status()).body(out.body());
    }

    @PostMapping("/reinspection-assignments/{assignmentId}/cancellations")
    ResponseEntity<CancellationResult> cancel(Authentication auth, @PathVariable UUID assignmentId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody CancellationBody body) {
        IdempotencyService.Outcome<CancellationResult> out = reinspections.cancel(CurrentUser.of(auth), assignmentId, key, body.reason(),
                body.version(), body.patrolVersion());
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
