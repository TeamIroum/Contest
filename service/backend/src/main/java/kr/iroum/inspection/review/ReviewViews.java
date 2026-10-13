package kr.iroum.inspection.review;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import kr.iroum.inspection.media.PrivacyReviewService.PrivacyRelease;

/** 관리자 검토 응답(OpenAPI ReviewTasks·ReviewTask·AdminInspection·Decision·Classification). 등급·severity·action은 넣지 않는다(D-13). */
public final class ReviewViews {

    private ReviewViews() {
    }

    public record Actor(UUID id, String name) {
    }

    public record Department(UUID id, String name) {
    }

    public record FacilitySummary(UUID id, String code, String type, String name, String areaLabel) {
    }

    public record FacilityDetail(UUID id, String code, String type, String name, String areaLabel, Department managingDepartment) {
    }

    public record ReviewTaskItem(UUID id, String type, String status, long version, String triggerReason, FacilitySummary facility,
            UUID patrolId, String patrolDate, UUID stopId, boolean hasPhoto, int inspectionCount, String reviewCategory, Instant createdAt,
            Actor claimedBy) {
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements) {
    }

    public record PatrolRef(UUID id, LocalDate date, String teamName, String routeName) {
    }

    public record StopRef(UUID id, int order, String name) {
    }

    public record SlotRef(UUID id, String label, String purpose) {
    }

    public record CapturedMember(UUID participantId, String name) {
    }

    public record CapturedBy(String teamName, List<CapturedMember> members) {
    }

    /**
     * mediaVersion: 가림 회차(AI 이미지 revision). version: 사진 기록 version(media.version) — 개인정보 검토
     * (POST /admin/media/{id}/privacy-reviews·privacy-release-retries)가 요구하는 낙관적 잠금 값. (service/: 화면이 판정을 보낼 수 있게 추가)
     */
    public record Media(UUID id, int mediaVersion, String privacyStatus, String contentPath, PrivacyRelease privacyRelease, long version) {
    }

    public record Finding(String facilityType, String code, String label, List<Double> bbox, String evidence) {
    }

    public record FacilityMatch(String aiFacilityType, String ledgerFacilityType, boolean matched) {
    }

    public record Retry(boolean pending, int attempts, Instant nextAt, boolean gaveUp) {
    }

    public record Analysis(UUID analysisResultId, int snapshotNo, String status, String reviewCategory, List<Finding> findings,
            FacilityMatch facilityMatch, Retry retry, String modelVersion, String promptVersion, Instant analyzedAt) {
    }

    public record TaskClassification(String source, String facilityType, List<String> issueTypes, int classificationVersion,
            UUID correctionId) {
    }

    public record TaskInspection(UUID id, int attemptNo, CapturedBy capturedBy, Instant capturedAt, Instant receivedAt, String receiptStatus,
            String captureStatus, String retakeReason, Media media, Analysis analysis, TaskClassification classification) {
    }

    public record OpenIssue(UUID id, String status, String defectCode, Instant openedAt, String summary) {
    }

    public record IssueResult(String mode, UUID inspectionId, UUID issueId, String defectCode) {
    }

    public record DecisionHistory(UUID reviewId, String decision, String followUpType, UUID analysisResultId, UUID correctionId,
            Integer classificationVersion, String reason, Actor decidedBy, Instant decidedAt, List<UUID> issueIds, List<IssueResult> issueResults) {
    }

    public record ReviewTask(UUID id, String type, String status, long version, String triggerReason, List<String> allowedDecisions,
            FacilityDetail facility, PatrolRef patrol, StopRef stop, SlotRef captureSlot, List<TaskInspection> inspections,
            List<OpenIssue> openIssues, List<DecisionHistory> decisions, Instant createdAt) {
    }

    public record AiClassification(UUID analysisResultId, String facilityType, List<String> issueTypes) {
    }

    public record CurrentClassification(String source, String facilityType, List<String> issueTypes, UUID correctionId) {
    }

    public record InspectionClassification(AiClassification ai, CurrentClassification current, int classificationVersion) {
    }

    public record AdminInspection(UUID id, UUID patrolId, UUID stopId, UUID captureSlotId, int attemptNo, FacilitySummary facility,
            CapturedBy capturedBy, Instant capturedAt, Instant receivedAt, String receiptStatus, String captureStatus, String retakeReason,
            boolean deviceMasked, Media media, List<Analysis> analyses, InspectionClassification classification, List<UUID> reviewTaskIds,
            List<UUID> linkedIssueIds) {
    }
}
