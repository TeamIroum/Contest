package kr.iroum.inspection.issue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.review.ReviewQueryService;

/**
 * 문제 변경 트랜잭션의 공통 시작(DB 설계 12절): 시설(1, 공유)을 잠근 뒤 현재 관할을 다시 확인하고(REVIEW-05), 문제(2)를 잠근다.
 * 잠금 전 확인은 존재를 숨기기 위한 것이고, 잠금 뒤 확인이 기준이다(잠금 대기 중 관할 이관).
 */
@Component
public class IssueLocks {

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;

    public IssueLocks(JdbcTemplate jdbc, AccessPolicy access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** 멱등 재현 전 범위 확인(시설 담당자·현재 관할). 사업 ID. */
    public UUID requireScope(AuthenticatedUser user, UUID issueId) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        UUID facility = jdbc.queryForList("SELECT facility_id FROM issues WHERE id = ?", UUID.class, issueId).stream().findFirst()
                .orElseThrow(ReviewQueryService::notFound);
        return access.requireFacility(user, facility);
    }

    /** 시설 공유 잠금 → 현재 관할 재확인 → 문제 FOR UPDATE. 잠근 문제 행. */
    public Map<String, Object> lock(AuthenticatedUser user, UUID issueId) {
        UUID facility = jdbc.queryForList("SELECT facility_id FROM issues WHERE id = ?", UUID.class, issueId).stream().findFirst()
                .orElseThrow(ReviewQueryService::notFound);
        jdbc.queryForList("SELECT id FROM facilities WHERE id = ? FOR SHARE", facility);
        access.requireFacility(user, facility);
        return jdbc.queryForMap("SELECT * FROM issues WHERE id = ? FOR UPDATE", issueId);
    }

    /** 문제 version 확인(409 VERSION_CONFLICT, details.currentVersion·which=issue). */
    public static void requireVersion(Map<String, Object> issue, long version) {
        long current = ((Number) issue.get("version")).longValue();
        if (current != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.",
                    Map.of("currentVersion", current, "which", "issue"));
        }
    }

    /** 허용 상태가 아니면 409 ISSUE_STATE_CONFLICT(details: issueId, currentStatus). */
    public static void requireStatus(Map<String, Object> issue, String... allowed) {
        String status = (String) issue.get("status");
        if (!List.of(allowed).contains(status)) {
            throw new ApiException(ErrorCode.ISSUE_STATE_CONFLICT, "문제의 현재 상태에서 할 수 없는 요청입니다. 최신 내용을 확인해 주세요.",
                    Map.of("issueId", issue.get("id"), "currentStatus", status));
        }
    }
}
