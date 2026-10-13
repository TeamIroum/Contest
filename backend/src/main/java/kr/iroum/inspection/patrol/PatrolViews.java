package kr.iroum.inspection.patrol;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 순회 응답 모델(OpenAPI Patrol·SyncState·OfflinePackage·TodayPatrols·AdminPatrol·AdminPatrols). 필드 이름이 계약이다. */
public final class PatrolViews {

    private PatrolViews() {
    }

    public record Actor(UUID id, String name) {
    }

    public record MeetingPoint(UUID id, String name) {
    }

    public record Progress(int total, int pending, int captured, int skipped, int reviewRequested, int notVisited) {
    }

    public record Contact(String name, String phoneNumber) {
    }

    public record Slot(UUID id, int order, String label, String instruction, boolean required, String purpose, String receiptStatus,
            String captureStatus, UUID activeInspectionId, UUID acceptedInspectionId, int retakesRemaining, UUID referenceMediaId,
            int qualityFailureCount) {
    }

    /** skipPending: 사진 처리 중이라 적용을 미룬 건너뛰기가 있음(장소는 PENDING 유지). */
    public record Stop(UUID id, int order, UUID facilityId, String facilityType, String name, String visitStatus, BigDecimal latitude,
            BigDecimal longitude, String instruction, List<Slot> captureSlots, boolean skipPending) {
    }

    /** 혼자 남은 대기(D-35): 대기 중인 조원과 서버가 대기를 기록한 시각. 대기가 아니면 null. */
    public record SoloWaiting(UUID participantId, Instant since) {
    }

    /** 본인 앱 복귀 요청의 최초 값(D-33). 확인 구분과 따로 보존한다. */
    public record ReturnRequest(String returnReason, String abortReason, Instant requestedAt) {
    }

    public record Outcomes(int normalReturned, int abortReturned, int transferredOut, int absent) {
    }

    /** 종료 당시 기록(불변). 조회할 때마다 바뀌지 않는다(설계안 3.3). */
    public record End(Instant endedAt, String endReason, String trigger, Outcomes outcomesAtEnd, String recordSource) {
    }

    /** 현재 집계(계산값). 종료 뒤 정정·보완을 저장할 때만 바뀐다. inField는 아직 복귀 확인 전인 현장 조원 수다. */
    public record CurrentOutcomes(int normalReturned, int abortReturned, int transferredOut, int absent, int inField) {
    }

    /** 종료 시각 뒤에 저장된 기록 보완 수와 마지막 보완 시각. */
    public record Supplement(int count, Instant lastAt) {
    }

    /**
     * 요청한 시니어 본인의 이 조 배정. active=false면 이 조에서 다른 조로 이동해 나간 것이며(조회·이동 전 사진 전송만),
     * transferredToPatrolId가 지금 조다. assignmentRef는 배정 행 ID로 오프라인 이벤트·사진에 함께 보낸다(설계안 4.7·4.8).
     */
    public record MyAssignment(UUID participantId, UUID assignmentRef, boolean active, Instant leftAt, UUID transferredToPatrolId) {
    }

    /** 이동해 나간 조원의 이 조 기록(관리자). */
    public record FormerMember(UUID participantId, UUID userId, String name, String attendance, Instant startedAt, String startSource,
            Instant leftAt, UUID transferredToPatrolId, UUID transferId) {
    }

    public record Member(UUID participantId, UUID userId, String name, int seatNo, String attendance, Instant attendanceRequestedAt,
            Instant startedAt, Instant startReceivedAt, String startSource, Instant returnedAt, String returnSource, boolean returned,
            Instant returnConfirmedAt, String returnReason, String abortReason, ReturnRequest returnRequest, boolean paused) {
    }

    public record Patrol(UUID id, long version, long revision, LocalDate date, String activityStatus, End end, String teamName, String routeName, MeetingPoint meetingPoint, Instant scheduledStartAt,
            Instant expectedReturnAt, Instant departedAt, Instant departureConfirmedAt, SoloWaiting soloWaiting, List<Member> members,
            Progress progress, UUID currentStopId, List<Stop> stops, Contact contact, boolean syncAllowed, Instant syncDeadlineAt,
            MyAssignment myAssignment) {
    }

    public record SyncMember(UUID participantId, String attendance, Instant startedAt, String startSource, Instant returnedAt,
            String returnSource, boolean returned, Instant returnConfirmedAt, String returnReason, String abortReason,
            ReturnRequest returnRequest, boolean paused) {
    }

    public record SyncSlot(UUID id, String receiptStatus, String captureStatus, UUID activeInspectionId, UUID acceptedInspectionId,
            int retakesRemaining, UUID referenceMediaId, int qualityFailureCount) {
    }

    public record SyncStop(UUID id, String visitStatus, List<SyncSlot> captureSlots, boolean skipPending) {
    }

    public record Asset(UUID mediaId, long mediaVersion, String contentPath) {
    }

    public record SyncState(UUID patrolId, long revision, long version, String activityStatus, End end, Instant departedAt, Instant departureConfirmedAt, SoloWaiting soloWaiting, boolean syncAllowed, Instant syncDeadlineAt,
            UUID currentStopId, Progress progress, List<SyncMember> members, List<SyncStop> stops, List<Asset> assets,
            List<UUID> revokedMediaIds, MyAssignment myAssignment) {
    }

    public record OfflinePackage(long packageVersion, Instant generatedAt, Patrol patrol, List<Asset> assets) {
    }

    public record TodayPatrol(UUID id, LocalDate date, String activityStatus, String teamName, String routeName, MeetingPoint meetingPoint,
            Instant scheduledStartAt, Instant expectedReturnAt, int memberCount, String myAttendance, Instant myAttendanceRequestedAt,
            Instant myStartedAt, Progress progress) {
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements) {
    }

    public record AttendanceSummary(int total, int present, int absent, int unchecked, int requested) {
    }

    public record AdminPatrolItem(UUID id, long version, LocalDate date, UUID organizationId, String organizationName, String teamName,
            String routeName, String activityStatus, Instant scheduledStartAt, Instant expectedReturnAt, Instant departedAt,
            Instant departureConfirmedAt, AttendanceSummary attendanceSummary, int startMissingCount, int returnedCount,
            boolean returnOverdue, Progress progress, Instant lastActivityAt, int pendingReturnConfirmations, SoloWaiting soloWaiting) {
    }

    public record AdminMember(UUID participantId, UUID userId, String name, int seatNo, String attendance, Instant attendanceRequestedAt,
            Instant startedAt, Instant startReceivedAt, String startSource, Instant returnedAt, String returnSource, String phoneNumber,
            Instant attendanceCheckedAt, Actor attendanceCheckedBy, String proxyAttendanceReason, Actor returnConfirmedBy,
            boolean startMissing, boolean returned, Instant returnConfirmedAt, String returnReason, String abortReason,
            ReturnRequest returnRequest, Instant joinedAt, UUID transferredFromPatrolId, boolean paused) {
    }

    public record RecentEvent(UUID id, String type, Actor actor, UUID stopId, String reasonCode, Instant occurredAt, Instant receivedAt) {
    }

    public record AdminPatrol(UUID id, long version, long revision, LocalDate date, String activityStatus, End end,
            CurrentOutcomes currentOutcomes, Supplement supplementedAfterEnd, int pendingReturnConfirmations, String teamName, String routeName, MeetingPoint meetingPoint, Instant scheduledStartAt,
            Instant expectedReturnAt, Instant departedAt, Instant departureConfirmedAt, SoloWaiting soloWaiting, List<AdminMember> members,
            List<FormerMember> formerMembers, Progress progress, UUID currentStopId, List<Stop> stops, boolean syncAllowed, Instant syncDeadlineAt, UUID organizationId,
            String organizationName, Actor departureConfirmedBy, boolean returnOverdue, Instant cancelledAt, Actor cancelledBy,
            String cancellationReason, List<RecentEvent> recentEvents) {
    }
}
