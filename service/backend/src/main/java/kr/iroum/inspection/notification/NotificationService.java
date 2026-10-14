package kr.iroum.inspection.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/**
 * 관리자 알림 조회·읽음(기능정의서 OPS-05, 업무 API GET /admin/notifications, PUT /admin/notifications/{id}/read).
 * <ul>
 * <li>주기적으로(기본 15초) 최근 하루 안의 새 일을 찾아 담당 범위의 관리자에게 알림 행을 만든다. 같은 사람·종류·대상은 한 번만.
 * 검토 요청 → 그 시설 관리 부서의 시설 담당자, 출석·복귀 요청과 재점검 요청 → 그 순회·시설을 맡은 활동 담당자,
 * '시설이 없어졌어요' 건너뛰기(SEN-07) → 그 시설 관리 부서의 시설 담당자.</li>
 * <li>화면 안 알림만 한다. 즉시 문자·푸시·긴급 대응 채널은 별도 합의(기능정의서 OPS-05).</li>
 * <li>알림 문구에는 전화번호를 넣지 않는다.</li>
 * </ul>
 * service/: 원본에는 알림 테이블만 있고 만들거나 보는 곳이 없었다(B-2).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Duration WINDOW = Duration.ofDays(1);

    public record Notification(UUID id, String type, String resourceType, UUID resourceId, String message, Instant createdAt, Instant readAt) {
    }

    public record Inbox(List<Notification> items, int unread) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, AccessPolicy access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Inbox list(AuthenticatedUser user, Boolean read) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER, AccessPolicy.FACILITY_MANAGER, AccessPolicy.PROGRAM_MANAGER, AccessPolicy.SYSTEM_ADMIN);
        List<Notification> items = jdbc.query("""
                SELECT id, type, resource_type, resource_id, message, created_at, read_at FROM notifications
                WHERE user_id = ? AND (?::boolean IS NULL OR (read_at IS NOT NULL) = ?) ORDER BY created_at DESC LIMIT 50""",
                (rs, i) -> new Notification(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()),
                user.userId(), read, read);
        Integer unread = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", Integer.class, user.userId());
        return new Inbox(items, unread == null ? 0 : unread);
    }

    /** 하나 읽음. id가 없으면 모두 읽음. */
    @Transactional
    public void markRead(AuthenticatedUser user, UUID id) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER, AccessPolicy.FACILITY_MANAGER, AccessPolicy.PROGRAM_MANAGER, AccessPolicy.SYSTEM_ADMIN);
        int n = jdbc.update("UPDATE notifications SET read_at = now() WHERE user_id = ? AND read_at IS NULL AND (?::uuid IS NULL OR id = ?)", user.userId(),
                id, id);
        if (id != null && n == 0) {
            Boolean mine = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM notifications WHERE id = ? AND user_id = ?)", Boolean.class, id, user.userId());
            if (!Boolean.TRUE.equals(mine)) {
                throw new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
            }
        }
    }

    @Scheduled(initialDelayString = "${inspection.notification.interval:15s}", fixedDelayString = "${inspection.notification.interval:15s}")
    @Transactional
    public void feed() {
        try {
            Timestamp since = Timestamp.from(clock.instant().minus(WINDOW));
            int n = 0;
            // 검토 요청 → 그 부서의 시설 담당자
            n += jdbc.update("""
                    INSERT INTO notifications (user_id, type, resource_type, resource_id, message)
                    SELECT DISTINCT s.user_id, 'REVIEW_REQUESTED', 'REVIEW_TASK', t.id, '새 검토 요청: ' || f.name
                    FROM review_tasks t JOIN facilities f ON f.id = t.facility_id
                    JOIN user_role_scopes s ON s.program_id = t.program_id AND s.organization_id = t.assigned_department_id
                         AND s.role = 'FACILITY_MANAGER' AND s.revoked_at IS NULL
                    WHERE t.created_at >= ? AND t.status <> 'RESOLVED'
                      AND NOT EXISTS (SELECT 1 FROM notifications x WHERE x.user_id = s.user_id AND x.type = 'REVIEW_REQUESTED' AND x.resource_id = t.id)""",
                    since);
            // 출석 요청·복귀 요청 → 그 순회의 수행기관·거점을 맡은 활동 담당자
            n += jdbc.update(MEMBER_EVENT.formatted("ATTENDANCE_REQUESTED", "출석 요청: ", "attendance_requested_at"), since);
            n += jdbc.update(MEMBER_EVENT.formatted("RETURN_REQUESTED", "복귀 요청: ", "return_requested_at"), since);
            // 재점검 요청 → 그 시설을 도는 순회를 맡은 활동 담당자
            n += jdbc.update("""
                    INSERT INTO notifications (user_id, type, resource_type, resource_id, message)
                    SELECT DISTINCT s.user_id, 'REINSPECTION_REQUESTED', 'REINSPECTION_REQUEST', r.id, '재점검 요청: ' || f.name || ' (순회에 배정해 주세요)'
                    FROM reinspection_requests r JOIN facilities f ON f.id = r.facility_id
                    JOIN patrol_stops ps ON ps.facility_id = r.facility_id JOIN patrols p ON p.id = ps.patrol_id
                    JOIN organizations b ON b.id = p.base_organization_id
                    JOIN user_role_scopes s ON s.program_id = p.program_id AND s.role = 'ACTIVITY_MANAGER' AND s.revoked_at IS NULL
                         AND (s.organization_id IN (p.organization_id, p.base_organization_id) OR (s.include_descendants AND s.organization_id = b.parent_id))
                    WHERE r.created_at >= ? AND r.status = 'OPEN'
                      AND NOT EXISTS (SELECT 1 FROM notifications x WHERE x.user_id = s.user_id AND x.type = 'REINSPECTION_REQUESTED' AND x.resource_id = r.id)""",
                    since);
            // 시설이 없어졌어요(SEN-07, 건너뛰기 사유 FACILITY_REMOVED) → 그 시설을 관리하는 부서의 시설 담당자 (시설 대장 확인)
            n += jdbc.update("""
                    INSERT INTO notifications (user_id, type, resource_type, resource_id, message)
                    SELECT DISTINCT s.user_id, 'FACILITY_REMOVED', 'FACILITY', f.id, '시설이 없어졌다는 현장 기록: ' || f.name || ' (시설 대장을 확인해 주세요)'
                    FROM patrol_events e JOIN patrol_stops ps ON ps.id = e.patrol_stop_id JOIN facilities f ON f.id = ps.facility_id
                    JOIN user_role_scopes s ON s.program_id = f.program_id AND s.organization_id = f.managing_department_id
                         AND s.role = 'FACILITY_MANAGER' AND s.revoked_at IS NULL
                    WHERE e.type = 'STOP_SKIPPED' AND e.reason_code = 'FACILITY_REMOVED' AND e.received_at >= ?
                      AND NOT EXISTS (SELECT 1 FROM notifications x WHERE x.user_id = s.user_id AND x.type = 'FACILITY_REMOVED' AND x.resource_id = f.id
                                      AND x.created_at >= ?)""",
                    since, since);
            if (n > 0) {
                log.info("알림 {}건 만듦", n);
            }
        } catch (RuntimeException e) {
            log.warn("알림 만들기 실패: {}", e.getClass().getSimpleName());
        }
    }

    private static final String MEMBER_EVENT = """
            INSERT INTO notifications (user_id, type, resource_type, resource_id, message)
            SELECT DISTINCT s.user_id, '%1$s', 'PATROL_MEMBER', m.id, '%2$s' || m.name_snapshot || ' (' || p.team_name_snapshot || ')'
            FROM patrol_members m JOIN patrols p ON p.id = m.patrol_id JOIN organizations b ON b.id = p.base_organization_id
            JOIN user_role_scopes s ON s.program_id = p.program_id AND s.role = 'ACTIVITY_MANAGER' AND s.revoked_at IS NULL
                 AND (s.organization_id IN (p.organization_id, p.base_organization_id) OR (s.include_descendants AND s.organization_id = b.parent_id))
            WHERE m.%3$s >= ? AND m.assignment_active
              AND NOT EXISTS (SELECT 1 FROM notifications x WHERE x.user_id = s.user_id AND x.type = '%1$s' AND x.resource_id = m.id)""";
}
