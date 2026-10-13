package kr.iroum.inspection.participant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse;
import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.patrol.PatrolViews.Page;

/**
 * 참여자 계정 관리(기능정의서 ACT-01). 활동 담당자가 관할 기관의 어르신을 등록하고 활동 상태를 바꾼다.
 * <ul>
 * <li>휴대폰 번호는 암호문과 검색용 HMAC으로만 저장하고, 화면에는 가운데를 가린 번호만 보낸다.</li>
 * <li>일시 중지·종료하면 인증 버전을 올리고 로그인 세션을 모두 끊는다. 다시 활동하면 새로 로그인해야 한다.</li>
 * <li>건강·편성 정보는 참여 신청서로 관리하고 여기에는 활동 가능 요일만 둔다.</li>
 * </ul>
 * service/: 원본에는 없던 기능이다(B-2).
 */
@Service
public class ParticipantService {

    public static final Set<String> DAYS = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED", "ENDED");
    private static final int MAX_PAGE_SIZE = 100;

    public record Organization(UUID id, String name) {
    }

    public record Participant(UUID id, UUID userId, String name, String phoneMasked, Organization organization, List<String> availableDays,
            String status, long version) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final PhoneCrypto phones;
    private final SessionService sessions;
    private final JsonMapper json;

    public ParticipantService(JdbcTemplate jdbc, AccessPolicy access, IdempotencyService idempotency, AuditLog audit, PhoneCrypto phones,
            SessionService sessions, JsonMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.idempotency = idempotency;
        this.audit = audit;
        this.phones = phones;
        this.sessions = sessions;
        this.json = json;
    }

    private static final String SELECT = """
            SELECT p.id, p.program_id, p.user_id, u.name, u.phone_ciphertext, p.organization_id, o.name AS organization_name,
                   p.available_schedule_json::text AS schedule, p.status, p.version
            FROM participants p JOIN users u ON u.id = p.user_id
            JOIN organizations o ON o.program_id = p.program_id AND o.id = p.organization_id
            """;

    /** 관할 기관(하위 기관 포함)의 참여자. 이름·번호 끝 4자리로 찾는다. */
    @Transactional(readOnly = true)
    public Page<Participant> list(AuthenticatedUser user, String status, String q, Integer page, Integer size) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 형식을 확인해 주세요.");
        }
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        if (p < 0 || s < 1 || s > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 형식을 확인해 주세요.");
        }
        List<Participant> all = jdbc.query(AccessPolicy.scopedOrganizationsCte() + SELECT + """
                WHERE EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = p.program_id AND sc.id = p.organization_id)
                  AND (?::text IS NULL OR p.status = ?)
                ORDER BY CASE p.status WHEN 'ACTIVE' THEN 0 WHEN 'SUSPENDED' THEN 1 ELSE 2 END, u.name, p.id""",
                this::map, user.userId(), AccessPolicy.ACTIVITY_MANAGER, status, status);
        // 번호는 암호문이라 SQL로 찾을 수 없다. 관할 참여자 수가 많지 않으므로 복호화한 뒤 거른다.
        String query = q == null ? "" : q.replaceAll("[\\s-]", "");
        List<Participant> matched = query.isEmpty() ? all
                : all.stream().filter(x -> x.name().replace(" ", "").contains(query) || x.phoneMasked().replace("-", "").endsWith(query)).toList();
        int from = Math.min(p * s, matched.size());
        return new Page<>(matched.subList(from, Math.min(from + s, matched.size())), p, s, matched.size());
    }

    /** 등록 화면에서 고를 수 있는 기관: 활동 담당자 관할 기관 중 수행기관·집결 거점. */
    @Transactional(readOnly = true)
    public List<Organization> organizations(AuthenticatedUser user) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        return jdbc.query(AccessPolicy.scopedOrganizationsCte() + """
                SELECT o.id, o.name FROM organizations o
                WHERE EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = o.program_id AND sc.id = o.id) AND o.type IN ('AGENCY', 'MEETING_POINT')
                ORDER BY CASE o.type WHEN 'AGENCY' THEN 0 ELSE 1 END, o.name""",
                (rs, i) -> new Organization(rs.getObject("id", UUID.class), rs.getString("name")), user.userId(), AccessPolicy.ACTIVITY_MANAGER);
    }

    @Transactional(readOnly = true)
    public Participant get(AuthenticatedUser user, UUID participantId) {
        return require(user, participantId, false);
    }

    @Transactional
    public IdempotencyService.Outcome<Participant> create(AuthenticatedUser user, UUID organizationId, String name, String phoneNumber,
            List<String> availableDays, UUID key) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        UUID programId = jdbc.query("SELECT program_id FROM organizations WHERE id = ?", (rs, i) -> rs.getObject(1, UUID.class), organizationId)
                .stream().findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다."));
        access.requireOrganization(user, AccessPolicy.ACTIVITY_MANAGER, programId, organizationId);
        String phone = normalizePhone(phoneNumber);
        String cleanName = cleanName(name);
        List<String> days = days(availableDays);
        Map<String, Object> identity = Map.of("organizationId", organizationId, "name", cleanName, "phone", java.util.HexFormat.of().formatHex(phones.lookupHmac(phone)),
                "days", days);
        return idempotency.execute(user.userId(), "participant.create", key, identity, Participant.class, 201, () -> {
            byte[] hmac = phones.lookupHmac(phone);
            Boolean taken = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE phone_lookup_hmac = ?)", Boolean.class, hmac);
            if (Boolean.TRUE.equals(taken)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "이미 등록된 휴대폰 번호예요.", null,
                        List.of(new ErrorResponse.FieldError("phoneNumber", "DUPLICATE", "이미 등록된 휴대폰 번호예요.")));
            }
            UUID userId = jdbc.queryForObject("""
                    INSERT INTO users (name, phone_ciphertext, phone_lookup_hmac) VALUES (?, ?, ?) RETURNING id""",
                    UUID.class, cleanName, phones.encrypt(phone), hmac);
            jdbc.update("INSERT INTO user_role_scopes (program_id, user_id, organization_id, role) VALUES (?, ?, ?, 'SENIOR')",
                    programId, userId, organizationId);
            UUID participantId = jdbc.queryForObject("""
                    INSERT INTO participants (program_id, user_id, organization_id, available_schedule_json)
                    VALUES (?, ?, ?, ?::jsonb) RETURNING id""",
                    UUID.class, programId, userId, organizationId, schedule(days));
            audit.record(programId, user.userId(), "PARTICIPANT_CREATED", "PARTICIPANT", participantId, null, null,
                    Map.of("name", cleanName, "organizationId", organizationId, "availableDays", days));
            return load(participantId);
        });
    }

    /** 이름·활동 가능 요일 수정. 번호 변경은 새 번호 확인 절차가 필요해 여기서 받지 않는다. */
    @Transactional
    public Participant update(AuthenticatedUser user, UUID participantId, String name, List<String> availableDays, long version) {
        Participant before = require(user, participantId, true);
        checkVersion(before, version);
        String cleanName = name == null ? before.name() : cleanName(name);
        List<String> days = availableDays == null ? before.availableDays() : days(availableDays);
        jdbc.update("UPDATE users SET name = ?, updated_at = now() WHERE id = ?", cleanName, before.userId());
        jdbc.update("UPDATE participants SET available_schedule_json = ?::jsonb, version = version + 1, updated_at = now() WHERE id = ?",
                schedule(days), participantId);
        audit.record(programOf(participantId), user.userId(), "PARTICIPANT_UPDATED", "PARTICIPANT", participantId, null,
                Map.of("name", before.name(), "availableDays", before.availableDays()), Map.of("name", cleanName, "availableDays", days));
        return load(participantId);
    }

    /** 활동·일시 중지·종료. 중지·종료하면 그 어르신의 로그인 세션이 모두 끊긴다. */
    @Transactional
    public IdempotencyService.Outcome<Participant> changeStatus(AuthenticatedUser user, UUID participantId, String status, String reason,
            long version, UUID key) {
        if (!STATUSES.contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 형식을 확인해 주세요.", null,
                    List.of(new ErrorResponse.FieldError("status", "INVALID", "상태를 다시 골라 주세요.")));
        }
        Map<String, Object> identity = Map.of("participantId", participantId, "status", status, "reason", reason, "version", version);
        return idempotency.execute(user.userId(), "participant.status", key, identity, Participant.class, 200, () -> {
            Participant before = require(user, participantId, true);
            checkVersion(before, version);
            if (before.status().equals(status)) {
                return before;
            }
            if ("ENDED".equals(before.status())) {
                throw new ApiException(ErrorCode.ACTIVITY_STATE_CONFLICT, "참여를 마친 분은 다시 바꿀 수 없어요. 새로 등록해 주세요.",
                        Map.of("reasonCode", "PARTICIPANT_ENDED"));
            }
            jdbc.update("UPDATE participants SET status = ?, version = version + 1, updated_at = now() WHERE id = ?", status, participantId);
            if (!"ACTIVE".equals(status)) {
                jdbc.update("UPDATE users SET auth_version = auth_version + 1, updated_at = now() WHERE id = ?", before.userId());
                sessions.revokeAll(before.userId());
            }
            audit.record(programOf(participantId), user.userId(), "PARTICIPANT_STATUS_CHANGED", "PARTICIPANT", participantId, reason,
                    Map.of("status", before.status()), Map.of("status", status));
            return load(participantId);
        });
    }

    private Participant require(AuthenticatedUser user, UUID participantId, boolean lock) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT program_id, organization_id FROM participants WHERE id = ?" + (lock ? " FOR UPDATE" : ""), participantId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
        }
        access.requireOrganization(user, AccessPolicy.ACTIVITY_MANAGER, (UUID) rows.get(0).get("program_id"),
                (UUID) rows.get(0).get("organization_id"));
        return load(participantId);
    }

    private Participant load(UUID participantId) {
        return jdbc.query(SELECT + "WHERE p.id = ?", this::map, participantId).get(0);
    }

    private UUID programOf(UUID participantId) {
        return jdbc.queryForObject("SELECT program_id FROM participants WHERE id = ?", UUID.class, participantId);
    }

    private static void checkVersion(Participant current, long version) {
        if (current.version() != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.",
                    Map.of("currentVersion", current.version()));
        }
    }

    private Participant map(ResultSet rs, int i) throws SQLException {
        byte[] cipher = rs.getBytes("phone_ciphertext");
        return new Participant(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"),
                cipher == null ? null : mask(phones.decrypt(cipher)),
                new Organization(rs.getObject("organization_id", UUID.class), rs.getString("organization_name")),
                parseDays(rs.getString("schedule")), rs.getString("status"), rs.getLong("version"));
    }

    /** 01012345678 → 010-****-5678 */
    static String mask(String normalized) {
        if (normalized == null || normalized.length() < 8) {
            return null;
        }
        return normalized.substring(0, 3) + "-****-" + normalized.substring(normalized.length() - 4);
    }

    private static String normalizePhone(String raw) {
        String phone;
        try {
            phone = PhoneCrypto.normalize(raw);
        } catch (RuntimeException e) {
            phone = null;
        }
        if (phone == null || !phone.matches("01[016789][0-9]{7,8}")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "휴대폰 번호를 다시 확인해 주세요.", null,
                    List.of(new ErrorResponse.FieldError("phoneNumber", "INVALID", "010-0000-0000 형식으로 넣어 주세요.")));
        }
        return phone;
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 50) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "이름을 확인해 주세요.", null,
                    List.of(new ErrorResponse.FieldError("name", "INVALID", "이름은 1~50자로 넣어 주세요.")));
        }
        return n;
    }

    private static List<String> days(List<String> days) {
        if (days == null || days.isEmpty() || !DAYS.containsAll(days)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "활동 가능 요일을 확인해 주세요.", null,
                    List.of(new ErrorResponse.FieldError("availableDays", "INVALID", "활동 가능 요일을 하나 이상 골라 주세요.")));
        }
        List<String> order = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
        return order.stream().filter(days::contains).toList();
    }

    private String schedule(List<String> days) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("days", days);
        return json.writeValueAsString(s);
    }

    private List<String> parseDays(String schedule) {
        if (schedule == null) {
            return List.of();
        }
        Object days = json.readValue(schedule, Map.class).get("days");
        List<String> out = new ArrayList<>();
        if (days instanceof List<?> l) {
            l.forEach(d -> out.add(String.valueOf(d)));
        }
        return out;
    }
}
