package kr.iroum.inspection.review.web;

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
import kr.iroum.inspection.review.ClassificationService;
import kr.iroum.inspection.review.ClassificationService.ClassificationResult;
import kr.iroum.inspection.review.ClassificationService.HistoryItem;
import kr.iroum.inspection.review.ReviewViews.Page;

/** 분류 정정과 정정 이력(업무 API 9.2절). */
@RestController
@RequestMapping("/api/v1/admin/inspections/{inspectionId}/classification-corrections")
public class AdminClassificationController {

    public record ClassificationRequest(UUID analysisResultId, String facilityType, List<String> issueTypes, String reason,
            Integer classificationVersion) {
    }

    private final ClassificationService classifications;

    public AdminClassificationController(ClassificationService classifications) {
        this.classifications = classifications;
    }

    @PostMapping
    ResponseEntity<ClassificationResult> correct(Authentication auth, @PathVariable UUID inspectionId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody ClassificationRequest body) {
        IdempotencyService.Outcome<ClassificationResult> out = classifications.correct(CurrentUser.of(auth), inspectionId, key,
                body.analysisResultId(), body.facilityType(), body.issueTypes(), body.reason(), body.classificationVersion());
        return ResponseEntity.status(out.status()).body(out.body());
    }

    @GetMapping
    Page<HistoryItem> history(Authentication auth, @PathVariable UUID inspectionId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return classifications.history(CurrentUser.of(auth), inspectionId, page, size);
    }
}
