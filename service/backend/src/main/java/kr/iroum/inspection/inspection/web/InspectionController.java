package kr.iroum.inspection.inspection.web;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.inspection.CaptureResults.CaptureResult;
import kr.iroum.inspection.inspection.CaptureResults.PhotoSubmitResult;
import kr.iroum.inspection.inspection.PhotoIntakeService;
import kr.iroum.inspection.inspection.PhotoUpload;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시니어 사진 제출·처리 결과 조회(업무 API 6.3절). multipart 파일은 메모리에서만 읽는다(임시 파일 임계값은 application.yml).
 * 글자 필드는 문자열로 받아 서비스가 형식 오류를 fieldErrors로 모아 돌려준다.
 */
@RestController
@RequestMapping("/api/v1")
public class InspectionController {

    private final PhotoIntakeService intake;
    private final JsonMapper json;

    public InspectionController(PhotoIntakeService intake, JsonMapper json) {
        this.intake = intake;
        this.json = json;
    }

    @PostMapping(path = "/patrols/{patrolId}/stops/{stopId}/inspections", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<PhotoSubmitResult> submit(Authentication auth, @PathVariable UUID patrolId, @PathVariable UUID stopId,
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(required = false) String clientSubmissionId, @RequestParam(required = false) String captureSlotId,
            @RequestParam(required = false) String attemptId, @RequestParam(required = false) String capturedAt,
            @RequestParam(required = false) String maskingVersion, @RequestParam(required = false) String deviceMasked,
            @RequestParam(required = false) String deviceMaskReport, @RequestParam(required = false) String latitude,
            @RequestParam(required = false) String longitude, @RequestParam(required = false) String locationAccuracyMeters,
            @RequestParam(required = false) String assignmentRef) throws IOException {
        PhotoUpload upload = PhotoUpload.parse(new PhotoUpload.Raw(clientSubmissionId, captureSlotId, attemptId, capturedAt, maskingVersion,
                deviceMasked, deviceMaskReport, latitude, longitude, locationAccuracyMeters, assignmentRef), json);
        PhotoIntakeService.Submitted result = intake.submit(CurrentUser.of(auth), patrolId, stopId, upload,
                file == null ? null : file.getBytes());
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** 서버 보관본 기술 재처리(업무 API 6.4절). 202는 접수 시점 상태이며 결과는 capture-status로 확인한다. */
    @PostMapping("/inspections/{inspectionId}/processing-retries")
    ResponseEntity<CaptureResult> retryProcessing(Authentication auth, @PathVariable UUID inspectionId,
            @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") UUID key) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED).body(intake.retryProcessing(CurrentUser.of(auth), inspectionId, key));
    }

    @GetMapping("/inspections/{inspectionId}/capture-status")
    CaptureResult captureStatus(Authentication auth, @PathVariable UUID inspectionId) {
        return intake.status(CurrentUser.of(auth), inspectionId);
    }
}
