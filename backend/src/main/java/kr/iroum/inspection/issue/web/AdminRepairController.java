package kr.iroum.inspection.issue.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.issue.RepairService;
import kr.iroum.inspection.issue.RepairService.RepairResult;

/** 보수 요청·착수·완료(업무 API 10절). */
@RestController
@RequestMapping("/api/v1/admin/issues/{issueId}")
public class AdminRepairController {

    public record RepairRequestBody(UUID assignedDepartmentId, String memo, Long version) {
    }

    public record RepairStartBody(Long version) {
    }

    public record RepairCompletionBody(String description, Instant completedAt, List<UUID> evidenceMediaIds, Long version) {
    }

    private final RepairService repairs;

    public AdminRepairController(RepairService repairs) {
        this.repairs = repairs;
    }

    @PostMapping("/repair-requests")
    ResponseEntity<RepairResult> request(Authentication auth, @PathVariable UUID issueId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody RepairRequestBody body) {
        return respond(repairs.request(CurrentUser.of(auth), issueId, key, body.assignedDepartmentId(), body.memo(), body.version()));
    }

    @PostMapping("/repair-starts")
    ResponseEntity<RepairResult> start(Authentication auth, @PathVariable UUID issueId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody RepairStartBody body) {
        return respond(repairs.start(CurrentUser.of(auth), issueId, key, body.version()));
    }

    @PostMapping("/repair-completions")
    ResponseEntity<RepairResult> complete(Authentication auth, @PathVariable UUID issueId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody RepairCompletionBody body) {
        return respond(repairs.complete(CurrentUser.of(auth), issueId, key, body.description(), body.completedAt(), body.evidenceMediaIds(),
                body.version()));
    }

    private static ResponseEntity<RepairResult> respond(IdempotencyService.Outcome<RepairResult> out) {
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
