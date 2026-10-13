package kr.iroum.inspection.review.web;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.review.ReviewQueryService;
import kr.iroum.inspection.review.ReviewViews.AdminInspection;
import kr.iroum.inspection.review.ReviewViews.Page;
import kr.iroum.inspection.review.ReviewViews.ReviewTask;
import kr.iroum.inspection.review.ReviewViews.ReviewTaskItem;

/** 시설 담당자 검토 조회(업무 API 9절). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminReviewController {

    enum TaskType {
        INSPECTION, CAPTURE_FAILURE, REPAIR_VERIFICATION
    }

    enum TaskStatus {
        OPEN, IN_REVIEW, RESOLVED
    }

    enum FacilityType {
        SIDEWALK, DRAIN, BENCH, SAFETY_FENCE, EXERCISE_EQUIPMENT, PARK_EQUIPMENT, SNOW_REMOVAL_BIN, SHADE_STRUCTURE
    }

    enum TriggerReason {
        ISSUE_SUSPECTED, AI_NEEDS_REVIEW, NO_ISSUE_SAMPLE, QUALITY_RETRY_EXHAUSTED, TECHNICAL_FAILURE, PRIVACY_HOLD, FACILITY_MISMATCH
    }

    private final ReviewQueryService queries;

    public AdminReviewController(ReviewQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/review-tasks")
    Page<ReviewTaskItem> list(Authentication auth, @RequestParam(required = false) TaskType type,
            @RequestParam(required = false) TaskStatus status, @RequestParam(required = false) FacilityType facilityType,
            @RequestParam(required = false) TriggerReason triggerReason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return queries.list(CurrentUser.of(auth), name(type), name(status), name(facilityType), name(triggerReason), from, to, page, size);
    }

    @GetMapping("/review-tasks/{reviewTaskId}")
    ReviewTask task(Authentication auth, @PathVariable UUID reviewTaskId) {
        return queries.task(CurrentUser.of(auth), reviewTaskId);
    }

    @GetMapping("/inspections/{inspectionId}")
    AdminInspection inspection(Authentication auth, @PathVariable UUID inspectionId) {
        return queries.inspection(CurrentUser.of(auth), inspectionId);
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }
}
