package kr.iroum.inspection.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import kr.iroum.inspection.common.idempotency.IdempotencyService;

/**
 * 코스·버전 관리(기능정의서 ACT-03, 업무 API 5절 /admin/routes).
 * <ul>
 * <li>코스는 출발·복귀 거점과 순서 있는 장소(시설), 장소별 필수 구도로 이뤄진다. 같은 시설을 다른 순서로 다시 넣을 수 있다.</li>
 * <li>공개한 버전은 고치지 않는다. 바꾸려면 새 버전을 만들어 바로 공개한다. 이미 배정된 순회는 배정 당시 버전을 복사해 두어 바뀌지 않는다.</li>
 * </ul>
 * service/: 원본에는 없던 기능이다(B-2).
 */
@Service
public class RouteService {

    public record Named(UUID id, String name) {
    }

    public record Slot(String label, String instruction, boolean required) {
    }

    public record Stop(UUID facilityId, String facilityName, String facilityType, String instruction, List<Slot> slots) {
    }

    public record RouteVersion(UUID id, int versionNo, Named departureBase, Named returnBase, List<Stop> stops) {
    }

    public record Route(UUID id, String name, String status, UUID organizationId, RouteVersion current, long version) {
    }

    public record Facility(UUID id, String code, String name, String type, String areaLabel) {
    }

    public record Options(List<Named> bases, List<Facility> facilities) {
    }

    /** 요청의 장소 하나: 시설, 안내, 구도(1~5개). */
    public record StopInput(UUID facilityId, String instruction, List<Slot> slots) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final IdempotencyService idempotency;
    private final AuditLog audit;

    public RouteService(JdbcTemplate jdbc, AccessPolicy access, IdempotencyService idempotency, AuditLog audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    /** 관할 기관의 코스와 최신 공개 버전. 닫은 코스는 includeInactive일 때만. */
    @Transactional(readOnly = true)
    public List<Route> list(AuthenticatedUser user, boolean includeInactive) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        List<UUID> ids = jdbc.query(AccessPolicy.scopedOrganizationsCte() + """
                SELECT r.id FROM routes r
                WHERE EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = r.program_id AND sc.id = r.organization_id)
                  AND (? OR r.status = 'ACTIVE')
                ORDER BY r.status, r.name, r.id""",
                (rs, i) -> rs.getObject(1, UUID.class), user.userId(), AccessPolicy.ACTIVITY_MANAGER, includeInactive);
        return ids.stream().map(this::load).toList();
    }

    /** 코스 편집에 고를 수 있는 거점(관할 수행기관과 그 아래 집결 거점)과 사업의 활성 시설. */
    @Transactional(readOnly = true)
    public Options options(AuthenticatedUser user) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        List<Named> bases = jdbc.query(AccessPolicy.scopedOrganizationsCte() + """
                SELECT o.id, o.name FROM organizations o
                WHERE o.type IN ('AGENCY', 'MEETING_POINT')
                  AND EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = o.program_id AND (sc.id = o.id OR sc.id = o.parent_id))
                ORDER BY CASE o.type WHEN 'MEETING_POINT' THEN 0 ELSE 1 END, o.name""",
                (rs, i) -> new Named(rs.getObject(1, UUID.class), rs.getString(2)), user.userId(), AccessPolicy.ACTIVITY_MANAGER);
        List<Facility> facilities = jdbc.query(AccessPolicy.scopedOrganizationsCte() + """
                SELECT f.id, f.facility_code, f.name, f.type, f.area_label FROM facilities f
                WHERE f.status = 'ACTIVE' AND f.program_id IN (SELECT program_id FROM scoped)
                ORDER BY f.area_label NULLS LAST, f.facility_code""",
                (rs, i) -> new Facility(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                user.userId(), AccessPolicy.ACTIVITY_MANAGER);
        return new Options(bases, facilities);
    }

    @Transactional
    public IdempotencyService.Outcome<Route> create(AuthenticatedUser user, UUID organizationId, String name, UUID departureBaseId, UUID returnBaseId,
            List<StopInput> stops, UUID key) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        UUID programId = jdbc.query("SELECT program_id FROM organizations WHERE id = ?", (rs, i) -> rs.getObject(1, UUID.class), organizationId)
                .stream().findFirst().orElseThrow(RouteService::notFound);
        access.requireOrganization(user, AccessPolicy.ACTIVITY_MANAGER, programId, organizationId);
        String cleanName = text(name, 100, "name", "코스 이름은 1~100자로 넣어 주세요.");
        Map<String, Object> identity = Map.of("organizationId", organizationId, "name", cleanName, "departure", departureBaseId, "return",
                returnBaseId, "stops", stops == null ? List.of() : stops);
        return idempotency.execute(user.userId(), "route.create", key, identity, Route.class, 201, () -> {
            UUID routeId = jdbc.queryForObject("INSERT INTO routes (program_id, organization_id, name) VALUES (?, ?, ?) RETURNING id", UUID.class,
                    programId, organizationId, cleanName);
            UUID versionId = publish(user, programId, routeId, departureBaseId, returnBaseId, stops);
            audit.record(programId, user.userId(), "ROUTE_CREATED", "ROUTE", routeId, null, null,
                    Map.of("name", cleanName, "routeVersionId", versionId));
            return load(routeId);
        });
    }

    /** 공개 버전은 고치지 않고 새 버전을 만들어 바로 공개한다. version은 코스 행의 version. */
    @Transactional
    public Route newVersion(AuthenticatedUser user, UUID routeId, UUID departureBaseId, UUID returnBaseId, List<StopInput> stops, long version) {
        UUID programId = lockOwned(user, routeId, version);
        UUID versionId = publish(user, programId, routeId, departureBaseId, returnBaseId, stops);
        jdbc.update("UPDATE routes SET version = version + 1, updated_at = now() WHERE id = ?", routeId);
        audit.record(programId, user.userId(), "ROUTE_VERSION_PUBLISHED", "ROUTE", routeId, null, null, Map.of("routeVersionId", versionId));
        return load(routeId);
    }

    /** 코스 이름·활성 상태. 닫은 코스는 새 순회에 고를 수 없고, 이미 배정된 순회는 그대로다. */
    @Transactional
    public Route update(AuthenticatedUser user, UUID routeId, String name, String status, long version) {
        UUID programId = lockOwned(user, routeId, version);
        Route before = load(routeId);
        String cleanName = name == null ? before.name() : text(name, 100, "name", "코스 이름은 1~100자로 넣어 주세요.");
        String nextStatus = status == null ? before.status() : status;
        if (!List.of("ACTIVE", "INACTIVE").contains(nextStatus)) {
            throw invalid("status", "INVALID", "상태를 다시 골라 주세요.");
        }
        jdbc.update("UPDATE routes SET name = ?, status = ?, version = version + 1, updated_at = now() WHERE id = ?", cleanName, nextStatus,
                routeId);
        audit.record(programId, user.userId(), "ROUTE_UPDATED", "ROUTE", routeId, null, Map.of("name", before.name(), "status", before.status()),
                Map.of("name", cleanName, "status", nextStatus));
        return load(routeId);
    }

    private UUID lockOwned(AuthenticatedUser user, UUID routeId, long version) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT program_id, organization_id, version FROM routes WHERE id = ? FOR UPDATE", routeId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        UUID programId = (UUID) rows.get(0).get("program_id");
        access.requireOrganization(user, AccessPolicy.ACTIVITY_MANAGER, programId, (UUID) rows.get(0).get("organization_id"));
        long current = ((Number) rows.get(0).get("version")).longValue();
        if (current != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.", Map.of("currentVersion", current));
        }
        return programId;
    }

    private UUID publish(AuthenticatedUser user, UUID programId, UUID routeId, UUID departureBaseId, UUID returnBaseId, List<StopInput> stops) {
        requireBase(user, programId, departureBaseId, "departureBaseId");
        requireBase(user, programId, returnBaseId, "returnBaseId");
        if (stops == null || stops.isEmpty() || stops.size() > 30) {
            throw invalid("stops", "COUNT", "장소를 1~30곳 넣어 주세요.");
        }
        Integer next = jdbc.queryForObject("SELECT coalesce(max(version_no), 0) + 1 FROM route_versions WHERE route_id = ?", Integer.class, routeId);
        UUID versionId = jdbc.queryForObject("""
                INSERT INTO route_versions (program_id, route_id, version_no, departure_base_id, return_base_id, published_at)
                VALUES (?, ?, ?, ?, ?, now()) RETURNING id""", UUID.class, programId, routeId, next, departureBaseId, returnBaseId);
        int seq = 1;
        for (StopInput s : stops) {
            Boolean ok = s.facilityId() != null && Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM facilities WHERE id = ? AND program_id = ? AND status = 'ACTIVE')", Boolean.class, s.facilityId(),
                    programId));
            if (!ok) {
                throw invalid("stops[" + (seq - 1) + "].facilityId", "INVALID", seq + "번째 장소의 시설을 다시 골라 주세요.");
            }
            if (s.slots() == null || s.slots().isEmpty() || s.slots().size() > 5) {
                throw invalid("stops[" + (seq - 1) + "].slots", "COUNT", seq + "번째 장소의 사진 구도를 1~5개 넣어 주세요.");
            }
            String instruction = optional(s.instruction(), 500);
            UUID stopId = jdbc.queryForObject("""
                    INSERT INTO route_stops (program_id, route_version_id, facility_id, sequence_no, instruction) VALUES (?, ?, ?, ?, ?) RETURNING id""",
                    UUID.class, programId, versionId, s.facilityId(), seq, instruction);
            int slotNo = 1;
            for (Slot slot : s.slots()) {
                String label = text(slot.label(), 50, "stops[" + (seq - 1) + "].slots", seq + "번째 장소의 구도 이름을 1~50자로 넣어 주세요.");
                jdbc.update("""
                        INSERT INTO route_capture_templates (program_id, route_stop_id, sequence_no, label, instruction, required)
                        VALUES (?, ?, ?, ?, ?, ?)""", programId, stopId, slotNo++, label, optional(slot.instruction(), 500), slot.required());
            }
            seq++;
        }
        return versionId;
    }

    private void requireBase(AuthenticatedUser user, UUID programId, UUID baseId, String field) {
        boolean ok = baseId != null && options(user).bases().stream().anyMatch(b -> b.id().equals(baseId))
                && programId.equals(jdbc.queryForObject("SELECT program_id FROM organizations WHERE id = ?", UUID.class, baseId));
        if (!ok) {
            throw invalid(field, "INVALID", "출발·복귀 거점을 다시 골라 주세요.");
        }
    }

    private Route load(UUID routeId) {
        Map<String, Object> r = jdbc.queryForMap("SELECT id, name, status, organization_id, version FROM routes WHERE id = ?", routeId);
        RouteVersion current = jdbc.query("""
                SELECT v.id, v.version_no, v.departure_base_id, d.name AS d_name, v.return_base_id, b.name AS b_name
                FROM route_versions v JOIN organizations d ON d.id = v.departure_base_id JOIN organizations b ON b.id = v.return_base_id
                WHERE v.route_id = ? AND v.published_at IS NOT NULL ORDER BY v.version_no DESC LIMIT 1""",
                (rs, i) -> new RouteVersion(rs.getObject("id", UUID.class), rs.getInt("version_no"),
                        new Named(rs.getObject("departure_base_id", UUID.class), rs.getString("d_name")),
                        new Named(rs.getObject("return_base_id", UUID.class), rs.getString("b_name")), stops(rs.getObject("id", UUID.class))),
                routeId).stream().findFirst().orElse(null);
        return new Route(routeId, (String) r.get("name"), (String) r.get("status"), (UUID) r.get("organization_id"), current,
                ((Number) r.get("version")).longValue());
    }

    private List<Stop> stops(UUID versionId) {
        Map<UUID, Stop> stops = new LinkedHashMap<>();
        jdbc.query("""
                SELECT s.id, s.facility_id, f.name, f.type, s.instruction FROM route_stops s JOIN facilities f ON f.id = s.facility_id
                WHERE s.route_version_id = ? ORDER BY s.sequence_no""", rs -> {
            stops.put(rs.getObject(1, UUID.class),
                    new Stop(rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), new ArrayList<>()));
        }, versionId);
        jdbc.query("""
                SELECT t.route_stop_id, t.label, t.instruction, t.required FROM route_capture_templates t
                JOIN route_stops s ON s.id = t.route_stop_id WHERE s.route_version_id = ? ORDER BY s.sequence_no, t.sequence_no""", rs -> {
            stops.get(rs.getObject(1, UUID.class)).slots().add(new Slot(rs.getString(2), rs.getString(3), rs.getBoolean(4)));
        }, versionId);
        return List.copyOf(stops.values());
    }

    private static String text(String value, int max, String field, String message) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > max) {
            throw invalid(field, "INVALID", message);
        }
        return v;
    }

    private static String optional(String value, int max) {
        String v = value == null ? "" : value.strip();
        if (v.length() > max) {
            throw invalid("instruction", "TOO_LONG", "안내는 " + max + "자 이내로 넣어 주세요.");
        }
        return v.isEmpty() ? null : v;
    }

    private static ApiException invalid(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, null, List.of(new ErrorResponse.FieldError(field, reason, message)));
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
    }
}
