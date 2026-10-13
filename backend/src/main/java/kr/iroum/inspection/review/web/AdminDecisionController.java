package kr.iroum.inspection.review.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.review.DecisionService;
import kr.iroum.inspection.review.DecisionService.DecisionResult;
import kr.iroum.inspection.review.DecisionService.Resolution;

/** 검토 작업 사람 판정(업무 API 9.1절). */
@RestController
public class AdminDecisionController {

    public record DecisionRequest(String decision, String followUpType, UUID analysisResultId, UUID correctionId, Integer classificationVersion,
            String reason, Long version, List<Resolution> issueResolutions) {
    }

    private final DecisionService decisions;

    public AdminDecisionController(DecisionService decisions) {
        this.decisions = decisions;
    }

    @PostMapping("/api/v1/admin/review-tasks/{reviewTaskId}/decisions")
    ResponseEntity<DecisionResult> decide(Authentication auth, @PathVariable UUID reviewTaskId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody DecisionRequest body) {
        IdempotencyService.Outcome<DecisionResult> out = decisions.decide(CurrentUser.of(auth), reviewTaskId, key, body.decision(),
                body.followUpType(), body.analysisResultId(), body.correctionId(), body.classificationVersion(), body.reason(), body.version(),
                body.issueResolutions());
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
