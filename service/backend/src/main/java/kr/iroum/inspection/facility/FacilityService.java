package kr.iroum.inspection.facility;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 시설 대장 관리(기능정의서 FAC-01, 업무 API /admin/facilities). 시설 담당자가 자기 부서가 관리하는 시설을 본다·추가한다·고친다.
 * <ul>
 * <li>시설 코드는 사업 안에서 하나. 종류·코드는 바꾸지 않는다(사진 판독·지난 기록의 기준). 이름·좌표·구역·상태·관리 부서를 고친다.</li>
 * <li>관리 부서를 다른 부서로 넘기면(담당 변경) 그 뒤 검토·사건은 새 부서가 본다. 넘긴 뒤에는 이 담당자의 목록에서 빠진다.</li>
 * <li>AI가 추정한 부서로 대장을 덮어쓰지 않는다. 바꾸는 것은 사람이 이 API로만 한다.</li>
 * <li>사용 중지(INACTIVE)한 시설은 새 코스에 넣을 수 없고, 지난 순회·사건 기록은 그대로다.</li>
 * </ul>
 * service/: 원본에는 시설 대장 관리 API가 없었다(B-2, 시연 시설은 시드로만 있었음).
 */
@Service
public class FacilityService {

    public static final Set<String> TYPES = Set.of("SIDEWALK", "DRAIN", "BENCH", "SAFETY_FENCE", "EXERCISE_EQUIPMENT", "PARK_EQUIPMENT",
            "SNOW_REMOVAL_BIN", "SHADE_STRUCTURE");

    public record Facility(UUID id, String code, String type, String name, BigDecimal latitude, BigDecimal longitude, String areaLabel,
            UUID departmentId, String departmentName, boolean publicManaged, String status, int openIssues, long version) {
    }

    public record Department(UUID id, String name, boolean mine) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final AuditLog audit;

    public FacilityService(JdbcTemplate jdbc, AccessPolicy access, AuditLog audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT f.id, f.facility_code, f.type, f.name, f.latitude, f.longitude, f.area_label, f.managing_department_id, o.name AS dept,
                   f.public_managed, f.status, f.version,
                   (SELECT count(*) FROM issues x WHERE x.facility_id = f.id AND x.status <> 'CLOSED') AS open_issues
            FROM facilities f JOIN organizations o ON o.id = f.managing_department_id""";

    @Transactional(readOnly = true)
    public List<Facility> list(AuthenticatedUser user) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        return jdbc.query(AccessPolicy.scopedOrganizationsCte() + SELECT + " " + """
                 WHERE EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = f.program_id AND sc.id = f.managing_department_id)
                 ORDER BY f.status, f.area_label NULLS LAST, f.facility_code""", this::map, user.userId(), AccessPolicy.FACILITY_MANAGER);
    }

    /** 관리 부서로 고를 수 있는 같은 사업의 부서(넘기기용). mine=내 관할 부서 */
    @Transactional(readOnly = true)
    public List<Department> departments(AuthenticatedUser user) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        return jdbc.query(AccessPolicy.scopedOrganizationsCte() + """
                SELECT o.id, o.name, EXISTS (SELECT 1 FROM scoped sc WHERE sc.id = o.id) AS mine FROM organizations o
                WHERE o.type = 'DEPARTMENT' AND o.status = 'ACTIVE' AND o.program_id IN (SELECT program_id FROM scoped)
                ORDER BY mine DESC, o.name""",
                (rs, i) -> new Department(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)), user.userId(), AccessPolicy.FACILITY_MANAGER);
    }

    @Transactional
    public Facility create(AuthenticatedUser user, String code, String type, String name, BigDecimal lat, BigDecimal lng, String area, UUID departmentId,
            boolean publicManaged) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        UUID program = jdbc.queryForList("SELECT program_id FROM organizations WHERE id = ? AND type = 'DEPARTMENT'", UUID.class, departmentId).stream()
                .findFirst().orElseThrow(() -> invalid("managingDepartmentId", "INVALID", "관리 부서를 다시 골라 주세요."));
        access.requireOrganization(user, AccessPolicy.FACILITY_MANAGER, program, departmentId);
        if (!TYPES.contains(type)) {
            throw invalid("type", "INVALID", "시설 종류를 다시 골라 주세요.");
        }
        String cleanCode = code == null ? "" : code.strip().toUpperCase();
        if (!cleanCode.matches("[A-Z0-9-]{2,30}")) {
            throw invalid("facilityCode", "INVALID", "시설 코드는 영문 대문자·숫자·- 2~30자로 넣어 주세요. 예: SW-0002");
        }
        Boolean taken = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM facilities WHERE program_id = ? AND facility_code = ?)", Boolean.class, program,
                cleanCode);
        if (Boolean.TRUE.equals(taken)) {
            throw invalid("facilityCode", "DUPLICATE", "이미 쓰고 있는 시설 코드예요.");
        }
        coords(lat, lng);
        UUID id = jdbc.queryForObject("""
                INSERT INTO facilities (program_id, facility_code, type, name, latitude, longitude, area_label, managing_department_id, public_managed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, program, cleanCode, type, text(name, 100, "name", "시설 이름은 1~100자로 넣어 주세요."),
                lat, lng, optional(area), departmentId, publicManaged);
        audit.record(program, user.userId(), "FACILITY_CREATED", "FACILITY", id, null, null,
                Map.of("facilityCode", cleanCode, "type", type, "managingDepartmentId", departmentId.toString()));
        return load(id);
    }

    @Transactional
    public Facility update(AuthenticatedUser user, UUID id, String name, BigDecimal lat, BigDecimal lng, boolean changeCoords, String area, String status,
            UUID departmentId, Boolean publicManaged, String reason, long version) {
        UUID program = access.requireFacility(user, id);
        Map<String, Object> row = jdbc.queryForMap("SELECT version FROM facilities WHERE id = ? FOR UPDATE", id);
        long current = ((Number) row.get("version")).longValue();
        if (current != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.", Map.of("currentVersion", current));
        }
        Facility before = load(id);
        String nextName = name == null ? before.name() : text(name, 100, "name", "시설 이름은 1~100자로 넣어 주세요.");
        BigDecimal nextLat = changeCoords ? lat : before.latitude();
        BigDecimal nextLng = changeCoords ? lng : before.longitude();
        coords(nextLat, nextLng);
        String nextArea = area == null ? before.areaLabel() : optional(area);
        String nextStatus = status == null ? before.status() : status;
        if (!List.of("ACTIVE", "INACTIVE").contains(nextStatus)) {
            throw invalid("status", "INVALID", "상태를 다시 골라 주세요.");
        }
        UUID nextDept = departmentId == null ? before.departmentId() : departmentId;
        if (!nextDept.equals(before.departmentId())) {
            // 담당 변경: 같은 사업의 활성 부서로만. 사유가 필요하다
            Boolean ok = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM organizations WHERE id = ? AND program_id = ? AND type = 'DEPARTMENT' AND status = 'ACTIVE')",
                    Boolean.class, nextDept, program);
            if (!Boolean.TRUE.equals(ok)) {
                throw invalid("managingDepartmentId", "INVALID", "관리 부서를 다시 골라 주세요.");
            }
            if (reason == null || reason.isBlank()) {
                throw invalid("reason", "REQUIRED", "관리 부서를 넘기는 이유를 넣어 주세요.");
            }
        }
        boolean nextPublic = publicManaged == null ? before.publicManaged() : publicManaged;
        jdbc.update("""
                UPDATE facilities SET name = ?, latitude = ?, longitude = ?, area_label = ?, status = ?, managing_department_id = ?, public_managed = ?,
                    version = version + 1, updated_at = now() WHERE id = ?""", nextName, nextLat, nextLng, nextArea, nextStatus, nextDept, nextPublic, id);
        Facility after = load(id);
        audit.record(program, user.userId(), "FACILITY_UPDATED", "FACILITY", id, reason == null || reason.isBlank() ? null : reason.strip(), snapshot(before),
                snapshot(after));
        return after;
    }

    private static Map<String, Object> snapshot(Facility f) {
        return Map.of("name", f.name(), "areaLabel", String.valueOf(f.areaLabel()), "status", f.status(), "managingDepartmentId", f.departmentId().toString(),
                "publicManaged", f.publicManaged(), "latitude", String.valueOf(f.latitude()), "longitude", String.valueOf(f.longitude()));
    }

    private Facility load(UUID id) {
        return jdbc.query(SELECT + " WHERE f.id = ?", this::map, id).get(0);
    }

    private Facility map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Facility(rs.getObject("id", UUID.class), rs.getString("facility_code"), rs.getString("type"), rs.getString("name"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("area_label"), rs.getObject("managing_department_id", UUID.class),
                rs.getString("dept"), rs.getBoolean("public_managed"), rs.getString("status"), rs.getInt("open_issues"), rs.getLong("version"));
    }

    private static void coords(BigDecimal lat, BigDecimal lng) {
        if ((lat == null) != (lng == null)) {
            throw invalid("latitude", "PAIR", "위도와 경도는 함께 넣어 주세요.");
        }
        if (lat != null && (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw invalid("latitude", "RANGE", "좌표 범위를 확인해 주세요.");
        }
    }

    private static String text(String value, int max, String field, String message) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > max) {
            throw invalid(field, "INVALID", message);
        }
        return v;
    }

    private static String optional(String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() > 100) {
            throw invalid("areaLabel", "TOO_LONG", "구역은 100자 이내로 넣어 주세요.");
        }
        return v.isEmpty() ? null : v;
    }

    private static ApiException invalid(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, null, List.of(new ErrorResponse.FieldError(field, reason, message)));
    }
}
