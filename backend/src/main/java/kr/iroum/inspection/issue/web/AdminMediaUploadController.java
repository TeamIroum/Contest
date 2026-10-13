package kr.iroum.inspection.issue.web;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.issue.RepairEvidenceService;
import kr.iroum.inspection.issue.RepairEvidenceService.EvidenceResult;

/** 참고·보수 사진 업로드(업무 API 10절 POST /admin/media). 지금은 보수 증거 사진만(06단계 E10). */
@RestController
public class AdminMediaUploadController {

    private final RepairEvidenceService evidence;

    public AdminMediaUploadController(RepairEvidenceService evidence) {
        this.evidence = evidence;
    }

    @PostMapping(path = "/api/v1/admin/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<EvidenceResult> upload(Authentication auth, @RequestHeader("Idempotency-Key") UUID key,
            @RequestParam(name = "file", required = false) MultipartFile file, @RequestParam(required = false) String purpose,
            @RequestParam(required = false) UUID issueId, @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String deviceMasked, @RequestParam(required = false) String deviceMaskReport,
            @RequestParam(required = false) String maskingVersion) throws IOException {
        IdempotencyService.Outcome<EvidenceResult> out = evidence.upload(CurrentUser.of(auth), key,
                new RepairEvidenceService.Upload(purpose, issueId, organizationId, deviceMasked, deviceMaskReport, maskingVersion),
                file == null ? null : file.getBytes());
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
