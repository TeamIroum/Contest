package kr.iroum.inspection.issue.web;

import java.util.List;
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
import kr.iroum.inspection.issue.VerificationService;
import kr.iroum.inspection.issue.VerificationService.DecisionResult;
import kr.iroum.inspection.issue.VerificationService.PreparationResult;
import kr.iroum.inspection.issue.VerificationService.Verifications;

/** 수리 확인 준비·판정·이력(업무 API 10절). */
@RestController
@RequestMapping("/api/v1/admin/issues/{issueId}")
public class AdminVerificationController {

    public record PreparationBody(UUID requestId, UUID repairEventId, String verificationMethod, List<UUID> beforeInspectionIds,
            List<UUID> afterInspectionIds, String fieldNote, Long version, Long requestVersion) {
    }

    public record DecisionBody(UUID verificationId, String decision, String followUpType, String reason, Long version, Long requestVersion,
            Long verificationVersion) {
    }

    private final VerificationService verifications;

    public AdminVerificationController(VerificationService verifications) {
        this.verifications = verifications;
    }

    @GetMapping("/verifications")
    Verifications list(Authentication auth, @PathVariable UUID issueId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return verifications.list(CurrentUser.of(auth), issueId, page, size);
    }

    @PostMapping("/verification-preparations")
    ResponseEntity<PreparationResult> prepare(Authentication auth, @PathVariable UUID issueId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody PreparationBody body) {
        IdempotencyService.Outcome<PreparationResult> out = verifications.prepare(CurrentUser.of(auth), issueId, key, body.requestId(),
                body.repairEventId(), body.verificationMethod(), body.beforeInspectionIds(), body.afterInspectionIds(), body.fieldNote(), body.version(),
                body.requestVersion());
        return ResponseEntity.status(out.status()).body(out.body());
    }

    @PostMapping("/verification-decisions")
    ResponseEntity<DecisionResult> decide(Authentication auth, @PathVariable UUID issueId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody DecisionBody body) {
        IdempotencyService.Outcome<DecisionResult> out = verifications.decide(CurrentUser.of(auth), issueId, key, body.verificationId(),
                body.decision(), body.followUpType(), body.reason(), body.version(), body.requestVersion(), body.verificationVersion());
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
