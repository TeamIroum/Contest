package kr.iroum.inspection.media;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/**
 * 권한 검사되는 사진 조회(GET /media/{id}/content, 업무 API 9절, 04단계 공백 G7). 요청마다 DB 현재 값(권한·관할·개인정보 상태)을 읽는다.
 * 문서 근거가 있는 경로만 연다. 역할 이름만으로 사진 전체를 허용하지 않는다.
 * <ul>
 * <li>FACILITY_MANAGER: 사진이 연결된 시설(점검 사진의 순회 장소, 참고 사진을 쓰는 구도의 장소)의 시설 대장상 현재 관리 부서가 본인 부서(업무 API 9절 관할).</li>
 * <li>SENIOR: 본인이 배정·참여(이동해 나간 순회 포함, D-36)한 순회의 구도가 쓰는 참고 사진(REFERENCE)만(업무 API 6.2절 assets·오프라인 패키지).
 * 점검 사진은 근거가 없어 열지 않는다.</li>
 * <li>ACTIVITY_MANAGER: 사진이 연결된 순회의 수행기관·거점이 담당 범위면 승인된 참고·점검 사진(확정 정책 2026-10-13). 사진 판정·개인정보 검토 권한은 없다.</li>
 * <li>PROGRAM_MANAGER·SYSTEM_ADMIN: 조회 범위를 정하지 않아 열지 않는다(403). 엑셀 권한은 사진 조회로 확장하지 않는다.</li>
 * </ul>
 * 공개 상태(APPROVED)이고 대체되지 않은(재가림 전 사본 아님)·삭제되지 않은 사진만 준다. 그 밖·범위 밖·없음은 404로 존재를 숨긴다.
 */
@Service
public class MediaContentService {

    public record Content(byte[] bytes, String contentType) {
    }

    private static final String NOT_FOUND = "찾을 수 없거나 접근할 수 없는 항목입니다.";

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final MediaStorage storage;

    public MediaContentService(JdbcTemplate jdbc, AccessPolicy access, MediaStorage storage) {
        this.jdbc = jdbc;
        this.access = access;
        this.storage = storage;
    }

    public Content content(AuthenticatedUser user, UUID mediaId) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER, AccessPolicy.ACTIVITY_MANAGER, AccessPolicy.SENIOR);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT bucket, object_key, content_type, purpose FROM media
                WHERE id = ? AND privacy_status = 'APPROVED' AND superseded_by_media_id IS NULL AND deleted_at IS NULL""", mediaId);
        if (rows.isEmpty() || !(facilityManagerMay(user, mediaId) || activityManagerMay(user, mediaId)
                || seniorMay(user, mediaId, (String) rows.get(0).get("purpose")))) {
            throw new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND);
        }
        Map<String, Object> m = rows.get(0);
        try {
            return new Content(storage.read((String) m.get("bucket"), (String) m.get("object_key")), (String) m.get("content_type"));
        } catch (java.io.UncheckedIOException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "사진을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    private boolean facilityManagerMay(AuthenticatedUser user, UUID mediaId) {
        if (!user.hasRole(AccessPolicy.FACILITY_MANAGER)) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(MediaLineage.afterScoped(AccessPolicy.scopedOrganizationsCte()) + """
                SELECT EXISTS (
                    SELECT 1 FROM facilities f JOIN scoped s ON s.program_id = f.program_id AND s.id = f.managing_department_id
                    WHERE f.id IN (
                        SELECT ps.facility_id FROM inspections i JOIN capture_slots c ON c.id = i.capture_slot_id
                            JOIN patrol_stops ps ON ps.id = c.patrol_stop_id
                        WHERE i.media_id IN (SELECT id FROM lineage) OR i.approved_media_id IN (SELECT id FROM lineage)
                        UNION SELECT ps.facility_id FROM capture_slots c JOIN patrol_stops ps ON ps.id = c.patrol_stop_id
                            WHERE c.reference_media_id IN (SELECT id FROM lineage)
                        UNION SELECT i.facility_id FROM media m JOIN issues i ON i.id = m.issue_id WHERE m.id IN (SELECT id FROM lineage)))""",
                Boolean.class, user.userId(), AccessPolicy.FACILITY_MANAGER, mediaId));
    }

    /** 활동 담당자: 사진(조상 사본 포함)이 연결된 순회의 수행기관 또는 거점이 담당 범위(확정 정책 2026-10-13). 점검·참고 사진 모두. */
    private boolean activityManagerMay(AuthenticatedUser user, UUID mediaId) {
        if (!user.hasRole(AccessPolicy.ACTIVITY_MANAGER)) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(MediaLineage.afterScoped(AccessPolicy.scopedOrganizationsCte()) + """
                SELECT EXISTS (
                    SELECT 1 FROM patrols p JOIN scoped s ON s.program_id = p.program_id AND s.id IN (p.organization_id, p.base_organization_id)
                    WHERE p.id IN (
                        SELECT ps.patrol_id FROM inspections i JOIN capture_slots c ON c.id = i.capture_slot_id
                            JOIN patrol_stops ps ON ps.id = c.patrol_stop_id
                        WHERE i.media_id IN (SELECT id FROM lineage) OR i.approved_media_id IN (SELECT id FROM lineage)
                        UNION SELECT ps.patrol_id FROM capture_slots c JOIN patrol_stops ps ON ps.id = c.patrol_stop_id
                            WHERE c.reference_media_id IN (SELECT id FROM lineage)))""",
                Boolean.class, user.userId(), AccessPolicy.ACTIVITY_MANAGER, mediaId));
    }

    private boolean seniorMay(AuthenticatedUser user, UUID mediaId, String purpose) {
        if (!user.hasRole(AccessPolicy.SENIOR) || !"REFERENCE".equals(purpose)) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM capture_slots c JOIN patrol_stops ps ON ps.id = c.patrol_stop_id
                    JOIN patrol_members m ON m.patrol_id = ps.patrol_id
                    JOIN participants pa ON pa.id = m.participant_id AND pa.program_id = m.program_id
                    JOIN patrols p ON p.id = ps.patrol_id
                    WHERE c.reference_media_id = ? AND pa.user_id = ? AND pa.status = 'ACTIVE' AND p.activity_status <> 'CANCELLED'
                      AND (m.assignment_active OR EXISTS (SELECT 1 FROM patrol_participations pp WHERE pp.patrol_id = m.patrol_id
                          AND pp.participant_id = m.participant_id AND pp.leave_reason = 'TRANSFERRED'))
                      AND EXISTS (SELECT 1 FROM user_role_scopes s WHERE s.user_id = pa.user_id AND s.program_id = m.program_id
                          AND s.role = 'SENIOR' AND s.revoked_at IS NULL))""",
                Boolean.class, mediaId, user.userId()));
    }
}
