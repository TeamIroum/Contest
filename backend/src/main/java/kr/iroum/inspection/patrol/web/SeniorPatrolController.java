package kr.iroum.inspection.patrol.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.patrol.command.AttendanceService;
import kr.iroum.inspection.patrol.command.ReturnService;
import kr.iroum.inspection.patrol.command.StartDepartureService;
import kr.iroum.inspection.patrol.command.StopEventService;
import kr.iroum.inspection.patrol.PatrolQueryService;
import kr.iroum.inspection.patrol.PatrolViews.OfflinePackage;
import kr.iroum.inspection.patrol.PatrolViews.Page;
import kr.iroum.inspection.patrol.PatrolViews.Patrol;
import kr.iroum.inspection.patrol.PatrolViews.SyncState;
import kr.iroum.inspection.patrol.PatrolViews.TodayPatrol;

/** 시니어 순회 조회(업무 API 6.2절). 활성 화면 5초 조회 전제이며 WebSocket은 두지 않는다. */
@RestController
@RequestMapping("/api/v1")
public class SeniorPatrolController {

    private final PatrolQueryService queries;
    private final AttendanceService attendance;
    private final StartDepartureService starts;
    private final ReturnService returns;
    private final StopEventService stopEvents;

    public SeniorPatrolController(PatrolQueryService queries, AttendanceService attendance, StartDepartureService starts,
            ReturnService returns, StopEventService stopEvents) {
        this.queries = queries;
        this.attendance = attendance;
        this.starts = starts;
        this.returns = returns;
        this.stopEvents = stopEvents;
    }

    public enum SkipReason {
        UNSAFE, NOT_FOUND, FACILITY_REMOVED, OTHER
    }

    public enum PauseType {
        PAUSE_STARTED, PAUSE_ENDED
    }

    /** assignmentRef: 오프라인 패키지·sync-state의 myAssignment.assignmentRef(선택, 멱등 해시 제외, 설계안 4.8). */
    public record SkipRequest(@NotNull SkipReason reason, @NotNull UUID clientEventId, @NotNull Instant occurredAt, UUID assignmentRef) {
    }

    public record PauseRequest(@NotNull PauseType type, @NotNull UUID clientEventId, @NotNull Instant occurredAt, UUID assignmentRef) {
    }

    @PostMapping("/patrols/{patrolId}/stops/{stopId}/skip-events")
    StopEventService.SkipResult skip(Authentication auth, @PathVariable UUID patrolId, @PathVariable UUID stopId,
            @Valid @RequestBody SkipRequest body) {
        return stopEvents.skip(CurrentUser.of(auth), patrolId, stopId, body.reason().name(), body.clientEventId(), body.occurredAt(),
                body.assignmentRef());
    }

    @PostMapping("/patrols/{patrolId}/pause-events")
    StopEventService.PauseResult pause(Authentication auth, @PathVariable UUID patrolId, @Valid @RequestBody PauseRequest body) {
        return stopEvents.pause(CurrentUser.of(auth), patrolId, body.type().name(), body.clientEventId(), body.occurredAt(), body.assignmentRef());
    }

    public enum ReturnReason {
        NORMAL, ABORT
    }

    public enum AbortReason {
        HEALTH, DEVICE, OTHER
    }

    public record ReturnRequest(@NotNull ReturnReason returnReason, AbortReason abortReason, @NotNull UUID clientEventId,
            @NotNull Instant occurredAt, UUID assignmentRef) {
    }

    @PostMapping("/patrols/{patrolId}/return-requests")
    ReturnService.ReturnResult requestReturn(Authentication auth, @PathVariable UUID patrolId, @Valid @RequestBody ReturnRequest body) {
        return returns.request(CurrentUser.of(auth), patrolId, body.returnReason().name(),
                body.abortReason() == null ? null : body.abortReason().name(), body.clientEventId(), body.occurredAt(), body.assignmentRef());
    }

    @PostMapping("/patrols/{patrolId}/activity-starts")
    StartDepartureService.ActivityStartResult start(Authentication auth, @PathVariable UUID patrolId,
            @Valid @RequestBody EventRequest body) {
        return starts.start(CurrentUser.of(auth), patrolId, body.clientEventId(), body.occurredAt(), body.assignmentRef());
    }

    public record EventRequest(@NotNull UUID clientEventId, @NotNull Instant occurredAt, UUID assignmentRef) {
    }

    @PostMapping("/patrols/{patrolId}/attendance-requests")
    AttendanceService.AttendanceRequestResult requestAttendance(Authentication auth, @PathVariable UUID patrolId,
            @Valid @RequestBody EventRequest body) {
        return attendance.request(CurrentUser.of(auth), patrolId, body.clientEventId(), body.occurredAt(), body.assignmentRef());
    }

    @GetMapping("/me/patrols")
    Page<TodayPatrol> today(Authentication auth, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return queries.today(CurrentUser.of(auth), date, page, size);
    }

    @GetMapping("/patrols/{patrolId}")
    Patrol patrol(Authentication auth, @PathVariable UUID patrolId) {
        return queries.patrol(CurrentUser.of(auth), patrolId);
    }

    @GetMapping("/patrols/{patrolId}/sync-state")
    SyncState syncState(Authentication auth, @PathVariable UUID patrolId) {
        return queries.syncState(CurrentUser.of(auth), patrolId);
    }

    @GetMapping("/patrols/{patrolId}/offline-package")
    OfflinePackage offlinePackage(Authentication auth, @PathVariable UUID patrolId) {
        return queries.offlinePackage(CurrentUser.of(auth), patrolId);
    }
}
