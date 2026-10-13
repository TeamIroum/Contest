package kr.iroum.inspection.media.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.media.PrivacyReviewService;
import kr.iroum.inspection.media.PrivacyReviewService.PrivacyResult;

/** 개인정보 검토 결정·보류 해제 재요청(업무 API 9·9.3절, D-28). 시설 담당자 전용. */
@RestController
@RequestMapping("/api/v1/admin/media")
public class AdminMediaController {

    private final PrivacyReviewService privacy;

    public AdminMediaController(PrivacyReviewService privacy) {
        this.privacy = privacy;
    }

    public record PrivacyReviewRequest(Boolean ok, String reason, Long version) {
    }

    public record PrivacyReleaseRetryRequest(UUID releaseId, Long version) {
    }

    @PostMapping("/{mediaId}/privacy-reviews")
    PrivacyResult review(Authentication auth, @PathVariable UUID mediaId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody PrivacyReviewRequest body) {
        return privacy.review(CurrentUser.of(auth), mediaId, body.ok(), body.reason(), body.version(), key);
    }

    @PostMapping("/{mediaId}/privacy-release-retries")
    ResponseEntity<PrivacyResult> retry(Authentication auth, @PathVariable UUID mediaId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody PrivacyReleaseRetryRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(privacy.retryRelease(CurrentUser.of(auth), mediaId, body.releaseId(), body.version(), key));
    }
}
