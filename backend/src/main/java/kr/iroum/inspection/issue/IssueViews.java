package kr.iroum.inspection.issue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import kr.iroum.inspection.review.ReviewViews.Actor;
import kr.iroum.inspection.review.ReviewViews.Department;
import kr.iroum.inspection.review.ReviewViews.FacilityDetail;
import kr.iroum.inspection.review.ReviewViews.FacilitySummary;
import kr.iroum.inspection.review.ReviewViews.Media;

/** 시설 문제·보수·재점검 응답(업무 API 10절, OpenAPI Issues·Issue). 위험도·등급·우선순위는 넣지 않는다(D-13). */
public final class IssueViews {

    private IssueViews() {
    }

    public record IssueItem(UUID id, String status, long version, String defectCode, String summary, FacilitySummary facility,
            Department assignedDepartment, int linkedInspectionCount, UUID currentReinspectionRequestId, Instant openedAt, Instant closedAt) {
    }

    /** 문제에 연결된 사진 시도. 해제된 연결도 이력으로 남긴다(unlinkedAt). media는 현재 사본이며 내용은 /media/{id}/content가 다시 검사한다. */
    public record IssueInspection(UUID inspectionId, UUID patrolId, LocalDate patrolDate, UUID captureSlotId, Instant capturedAt, Media media,
            Actor linkedBy, Instant linkedAt, String linkReason, Actor unlinkedBy, Instant unlinkedAt) {
    }

    /** 이 문제를 만들거나 연결한 사람 판정(D-25·26). */
    public record IssueReview(UUID reviewId, UUID reviewTaskId, String mode, UUID inspectionId, String decision, UUID analysisResultId,
            UUID correctionId, Integer classificationVersion, Actor decidedBy, Instant decidedAt) {
    }

    public record RepairEvent(UUID id, String type, Actor actor, Department department, String description, Instant occurredAt,
            Instant recordedAt, List<Media> evidenceMedia) {
    }

    public record ReinspectionAssignment(UUID id, UUID captureSlotId, UUID patrolId, LocalDate patrolDate, Instant assignedAt, Instant cancelledAt,
            Instant completedAt) {
    }

    public record ReinspectionRequest(UUID id, String requestType, String status, long version, UUID repairEventId, Instant createdAt,
            List<ReinspectionAssignment> assignments) {
    }

    public record IssueDetail(UUID id, String status, long version, String defectCode, String summary, FacilityDetail facility,
            Department assignedDepartment, UUID sourceReviewId, UUID previousIssueId, UUID currentReinspectionRequestId, Instant openedAt,
            Instant closedAt, List<IssueInspection> inspections, List<IssueReview> reviews, List<RepairEvent> repairEvents,
            List<ReinspectionRequest> reinspectionRequests) {
    }

    /** 재점검 요청 목록 항목. source: REPAIR_COMPLETION(보수 완료) / REVIEW_FOLLOW_UP(검토 판정의 추가 확인). 문제 내용·등급은 넣지 않는다. */
    public record ReinspectionRequestItem(UUID id, String requestType, String status, long version, String source, UUID issueId,
            UUID reviewDecisionId, UUID repairEventId, FacilitySummary facility, Instant createdAt, List<ReinspectionAssignment> assignments) {
    }

    public record AssignmentResult(UUID assignmentId, UUID requestId, long requestVersion, UUID captureSlotId, UUID patrolId, long patrolVersion,
            List<UUID> cancelledAssignmentIds) {
    }

    public record CancellationResult(UUID assignmentId, UUID requestId, long requestVersion, UUID patrolId, long patrolVersion, Instant cancelledAt) {
    }
}
