package kr.iroum.inspection.inspection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import kr.iroum.inspection.patrol.PatrolReader;
import kr.iroum.inspection.patrol.PatrolReader.SlotRow;
import kr.iroum.inspection.patrol.PatrolReader.StopRow;

/**
 * 사진 시도 하나의 현재 처리 결과(CaptureResult, 업무 API 6.3절). 제출 응답·상태 조회·멱등 재현이 같은 계산을 쓴다.
 * <ul>
 * <li>receiptStatus와 captureStatus는 저장된 값 그대로다. nextAction·다음 위치·안내 문장은 지금 구도·장소·활동 상태로 계산한다.</li>
 * <li>늦은 사진: 활동이 RETURNING·ENDED이거나 제출자가 복귀 확인됐거나 이 조에서 이동해 나갔으면 현장 재촬영·다음 장소를 안내하지 않고
 * RETURN_TO_CENTER(전송 완료 안내)다(D-27).</li>
 * <li>message는 서버가 만든 품질·진행 안내다. 시설 이상 내용·AI 원문 안내는 넣지 않는다.</li>
 * </ul>
 */
@Component
public class CaptureResults {

    /** 상태 조회 응답(OpenAPI CaptureResult). */
    public record CaptureResult(UUID inspectionId, UUID clientSubmissionId, int attemptNo, String receiptStatus, String captureStatus,
            String nextAction, String retakeReason, int retakesRemaining, String message, UUID stopId, UUID captureSlotId, UUID nextStopId,
            UUID nextCaptureSlotId, long patrolRevision, int qualityFailureCount) {

        public PhotoSubmitResult submitted(boolean duplicate) {
            return new PhotoSubmitResult(inspectionId, clientSubmissionId, attemptNo, duplicate, receiptStatus, captureStatus, nextAction,
                    retakeReason, retakesRemaining, message, stopId, captureSlotId, nextStopId, nextCaptureSlotId, patrolRevision,
                    qualityFailureCount);
        }
    }

    /** 제출 응답(OpenAPI PhotoSubmitResult): CaptureResult + duplicate. */
    public record PhotoSubmitResult(UUID inspectionId, UUID clientSubmissionId, int attemptNo, boolean duplicate, String receiptStatus,
            String captureStatus, String nextAction, String retakeReason, int retakesRemaining, String message, UUID stopId,
            UUID captureSlotId, UUID nextStopId, UUID nextCaptureSlotId, long patrolRevision, int qualityFailureCount) {
    }

    /** 결과 계산에 필요한 사진·구도·순회·제출자 상태. */
    public record Context(UUID inspectionId, UUID clientSubmissionId, int attemptNo, String receiptStatus, String captureStatus,
            String retakeReason, UUID slotId, UUID stopId, UUID patrolId, int qualityFailureCount, String slotResolution,
            UUID acceptedInspectionId, long patrolRevision, String activityStatus, boolean submitterActive, boolean submitterReturned,
            boolean aiPending) {

        /** 늦은 사진: 현장 재촬영·다음 장소를 안내하지 않는다(D-27). */
        public boolean late() {
            return "RETURNING".equals(activityStatus) || "ENDED".equals(activityStatus) || "CANCELLED".equals(activityStatus)
                    || !submitterActive || submitterReturned;
        }
    }

    private final JdbcTemplate jdbc;
    private final PatrolReader reader;

    public CaptureResults(JdbcTemplate jdbc, PatrolReader reader) {
        this.jdbc = jdbc;
        this.reader = reader;
    }

    public Optional<Context> context(UUID inspectionId) {
        return jdbc.query("""
                SELECT i.id, i.client_submission_id, i.attempt_no, i.receipt_status, i.capture_status, i.retake_reason, s.id, s.patrol_stop_id,
                    ps.patrol_id, s.quality_failure_count, s.resolution, s.accepted_inspection_id, p.revision, p.activity_status,
                    COALESCE(m.assignment_active, false), m.return_confirmed_at IS NOT NULL,
                    EXISTS (SELECT 1 FROM analysis_jobs j WHERE j.inspection_id = i.id AND j.retry_owner = 'AI' AND j.status = 'PROCESSING')
                FROM inspections i
                JOIN capture_slots s ON s.id = i.capture_slot_id
                JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                JOIN patrols p ON p.id = ps.patrol_id
                LEFT JOIN patrol_members m ON m.patrol_id = p.id AND m.user_id_snapshot = i.submitted_by
                WHERE i.id = ?""",
                (rs, i) -> new Context(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
                        rs.getInt(10), rs.getString(11), rs.getObject(12, UUID.class), rs.getLong(13), rs.getString(14), rs.getBoolean(15),
                        rs.getBoolean(16), rs.getBoolean(17)),
                inspectionId).stream().findFirst();
    }

    public CaptureResult of(UUID inspectionId) {
        return of(context(inspectionId).orElseThrow(), false);
    }

    /** otherAccepted: 다른 조원의 채택 사진을 대신 보여 줄 때(D-18 중복). */
    public CaptureResult of(Context c, boolean otherAccepted) {
        int remaining = Math.min(2, 3 - c.qualityFailureCount());
        String receipt = c.receiptStatus();
        String capture = c.captureStatus();
        if ("RECEIVING".equals(receipt) || "PROCESSING".equals(capture)) {
            return result(c, "WAIT", null, null, remaining,
                    "RECEIVED".equals(receipt) ? "사진을 다시 확인하고 있어요." : "사진을 확인하고 있어요. 잠시만 기다려 주세요.");
        }
        if ("RETRY_REQUIRED".equals(receipt)) {
            return result(c, "RETRY", null, null, remaining, "사진이 아직 전송되지 않았어요. 다시 보낼게요.");
        }
        if ("TECHNICAL_FAILED".equals(capture)) {
            if ("RECEIVED".equals(receipt)) {
                return result(c, "RETRY", null, null, remaining, "사진은 잘 받았어요. 확인을 다시 시도해 주세요.");
            }
            if (!c.late() && slotOpen(c)) {
                return result(c, "RETAKE", c.stopId(), c.slotId(), remaining, "사진을 처리하지 못했어요. 다시 찍어 주세요.");
            }
        }
        if ("RETAKE_REQUIRED".equals(capture) && !c.late() && slotOpen(c)) {
            return result(c, "RETAKE", c.stopId(), c.slotId(), remaining, retakeMessage(c.retakeReason()));
        }
        Next next = c.late() ? Next.CENTER : next(c);
        return result(c, next.action(), next.stopId(), next.slotId(), remaining, progressMessage(c, next.action(), otherAccepted));
    }

    private static boolean slotOpen(Context c) {
        return "OPEN".equals(c.slotResolution()) && c.acceptedInspectionId() == null;
    }

    private record Next(String action, UUID stopId, UUID slotId) {
        static final Next CENTER = new Next("RETURN_TO_CENTER", null, null);
    }

    /** 다음 안내: 같은 장소의 남은 구도 → 남은 장소(건너뛰기 보류 제외)의 첫 열린 구도 → 센터 복귀. 다른 조원이 처리 중인 구도는 건너뛴다. */
    private Next next(Context c) {
        List<StopRow> stops = reader.stops(c.patrolId());
        for (StopRow stop : stops) {
            if (stop.id().equals(c.stopId())) {
                Optional<SlotRow> open = firstOpen(stop);
                if (open.isPresent() && ("PENDING".equals(stop.visitStatus()) && !stop.skipPending())) {
                    return new Next("NEXT_CAPTURE", stop.id(), open.get().id());
                }
            }
        }
        for (StopRow stop : stops) {
            if (!"PENDING".equals(stop.visitStatus()) || stop.skipPending() || stop.id().equals(c.stopId())) {
                continue;
            }
            Optional<SlotRow> open = firstOpen(stop);
            if (open.isPresent()) {
                return new Next("NEXT_STOP", stop.id(), open.get().id());
            }
        }
        return Next.CENTER;
    }

    private static Optional<SlotRow> firstOpen(StopRow stop) {
        return stop.slots().stream().filter(s -> "OPEN".equals(s.resolution()) && s.activeInspectionId() == null).findFirst();
    }

    private static CaptureResult result(Context c, String action, UUID nextStop, UUID nextSlot, int remaining, String message) {
        return new CaptureResult(c.inspectionId(), c.clientSubmissionId(), c.attemptNo(), c.receiptStatus(), c.captureStatus(), action,
                c.retakeReason(), remaining, message, c.stopId(), c.slotId(), nextStop, nextSlot, c.patrolRevision(), c.qualityFailureCount());
    }

    static String retakeMessage(String reason) {
        return switch (reason == null ? "" : reason) {
            case "DARK" -> "사진이 어두워요. 밝은 쪽에서 한 번 더 찍어 주세요.";
            case "OUT_OF_FRAME" -> "찍을 곳이 네모 밖에 있어요. 네모 안에 맞춰 다시 찍어 주세요.";
            default -> "사진이 흐려요. 한 번 더 찍어 주세요.";
        };
    }

    private static String progressMessage(Context c, String action, boolean otherAccepted) {
        boolean center = "RETURN_TO_CENTER".equals(action);
        if (c.late() && center) {
            if ("TECHNICAL_FAILED".equals(c.captureStatus()) || ("REVIEW_REQUESTED".equals(c.captureStatus()) && "NO_MEDIA".equals(c.receiptStatus())
                    && c.retakeReason() == null)) {
                return "사진을 처리하지 못했어요. 다시 찍으러 가지 않으셔도 되고, 담당자가 확인할게요.";
            }
            if ("REVIEW_REQUESTED".equals(c.captureStatus())) {
                return "사진이 전송됐어요. 담당자가 확인할게요. 따로 하실 일은 없어요.";
            }
            return "사진이 전송됐어요. 활동은 이미 끝났으니 따로 하실 일은 없어요.";
        }
        String tail = switch (action) {
            case "NEXT_CAPTURE" -> "다음 사진을 찍어 주세요.";
            case "NEXT_STOP" -> "다음 장소로 이동해 주세요.";
            default -> "센터로 돌아가 주세요.";
        };
        if (otherAccepted) {
            return "같이 계신 분의 사진이 이미 접수됐어요. " + tail;
        }
        return switch (c.captureStatus()) {
            case "ACCEPTED" -> "사진이 잘 찍혔어요. " + tail;
            case "REVIEW_REQUESTED" -> c.retakeReason() != null
                    ? "담당자에게 확인을 요청했어요. " + switch (action) {
                        case "NEXT_CAPTURE" -> "다음 사진으로 넘어갈게요.";
                        case "NEXT_STOP" -> "다음 장소로 넘어갈게요.";
                        default -> "센터로 돌아가 주세요.";
                    }
                    : "담당자가 사진을 한 번 더 볼게요. " + ("NEXT_STOP".equals(action) ? "다음 장소로 가셔도 돼요." : tail);
            default -> "다른 조원이 이 사진 순서를 마쳤어요. " + tail;
        };
    }
}
