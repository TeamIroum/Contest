package kr.iroum.inspection.issue;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse;

/**
 * 사건 ↔ 점검 사진 연결 해제(기능정의서 FAC-06, 업무 API DELETE /admin/issues/{id}/inspection-links/{inspectionId}).
 * <ul>
 * <li>그 시설의 현재 관할 시설 담당자만, 종결 전 사건만. 사유와 사건 version이 필요하다.</li>
 * <li>행을 지우지 않고 unlinked_at·unlinked_by·사유를 남긴다(이력 보존). 사건 version을 올리고 감사 기록을 남긴다.</li>
 * <li>사건의 마지막 사진은 해제하지 않는다(근거 없는 사건이 되지 않게). 다시 연결은 사진 판정에서 한다.</li>
 * </ul>
 * service/: 원본에는 연결(판정 때)만 있고 해제 API가 없었다(B-2). 명세의 "전달 형식은 상세 계약에서 결정"에 따라 본문 JSON(reason, version)으로 받는다.
 */
@Service
public class IssueLinkService {

    public record Unlinked(UUID issueId, UUID inspectionId, long issueVersion, int remaining) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final AuditLog audit;
    private final Clock clock;

    public IssueLinkService(JdbcTemplate jdbc, AccessPolicy access, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Unlinked unlink(AuthenticatedUser user, UUID issueId, UUID inspectionId, String reason, long version) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "해제 사유를 1~500자로 넣어 주세요.", null,
                    List.of(new ErrorResponse.FieldError("reason", "REQUIRED", "해제 사유를 1~500자로 넣어 주세요.")));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT program_id, facility_id, status, version FROM issues WHERE id = ?", issueId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
        }
        UUID program = access.requireFacility(user, (UUID) rows.get(0).get("facility_id"));
        Map<String, Object> issue = jdbc.queryForMap("SELECT status, version FROM issues WHERE id = ? FOR UPDATE", issueId);
        long current = ((Number) issue.get("version")).longValue();
        if (current != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.", Map.of("currentVersion", current));
        }
        if ("CLOSED".equals(issue.get("status"))) {
            throw new ApiException(ErrorCode.ISSUE_STATE_CONFLICT, "종결된 사건의 사진 연결은 바꿀 수 없어요.", Map.of("status", "CLOSED"));
        }
        Integer linked = jdbc.queryForObject("SELECT count(*) FROM issue_inspections WHERE issue_id = ? AND unlinked_at IS NULL", Integer.class, issueId);
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM issue_inspections WHERE issue_id = ? AND inspection_id = ? AND unlinked_at IS NULL)",
                Boolean.class, issueId, inspectionId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "이 사건에 연결된 사진이 아니에요.");
        }
        if (linked != null && linked <= 1) {
            throw new ApiException(ErrorCode.ISSUE_STATE_CONFLICT, "사건의 마지막 사진은 해제할 수 없어요. 다른 사진을 먼저 연결해 주세요.",
                    Map.of("reason", "LAST_EVIDENCE"));
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE issue_inspections SET unlinked_at = ?, unlinked_by = ?, reason = ? WHERE issue_id = ? AND inspection_id = ? AND unlinked_at IS NULL""",
                now, user.userId(), why, issueId, inspectionId);
        long next = jdbc.queryForObject("UPDATE issues SET version = version + 1, updated_at = ? WHERE id = ? RETURNING version", Long.class, now, issueId);
        audit.record(program, user.userId(), "ISSUE_INSPECTION_UNLINKED", "ISSUE", issueId, why, Map.of("inspectionId", inspectionId.toString()), null);
        return new Unlinked(issueId, inspectionId, next, linked == null ? 0 : linked - 1);
    }
}
