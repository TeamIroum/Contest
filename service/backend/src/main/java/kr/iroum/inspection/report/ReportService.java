package kr.iroum.inspection.report;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import kr.iroum.inspection.analysis.client.AiClient;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.stats.StatsService;

/**
 * 기간 보고 문장(기능정의서 OPS-04). DB로 확정한 집계값만 항목(key·label·value·unit)으로 AI /report/narrative에 넘긴다.
 * <ul>
 * <li>AI가 입력에 없는 숫자를 쓰면 AI가 고정 문장을 돌려주고, AI에 연결할 수 없으면 백엔드가 같은 고정 문장을 만든다.</li>
 * <li>결과(집계값·문장·출처·만든 시각)는 report_jobs에 회차(revision_no)로 남긴다. AI를 부르는 동안 DB 트랜잭션을 잡지 않는다.</li>
 * </ul>
 * service/: 원본에는 없던 기능이다(B-2). 주간 고정이 아니라 통계 화면의 기간을 그대로 쓴다(week_start = 기간 시작일).
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    public record Item(String key, String label, long value, String unit) {
    }

    public record Narrative(UUID id, LocalDate from, LocalDate to, List<Item> items, String text, String source, String note, Instant generatedAt,
            int revision) {
    }

    private final StatsService stats;
    private final AiClient ai;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final JsonMapper json;
    private final Clock clock;

    public ReportService(StatsService stats, AiClient ai, JdbcTemplate jdbc, TransactionTemplate tx, AuditLog audit, JsonMapper json, Clock clock) {
        this.stats = stats;
        this.ai = ai;
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public Narrative generate(AuthenticatedUser user, LocalDate from, LocalDate to) {
        StatsService.Report r = stats.report(user, from, to); // 사업 총괄 권한 확인 포함
        StatsService.Summary s = r.summary();
        List<Item> items = List.of(new Item("patrols", "순회 횟수", s.patrols(), "회"), new Item("participant_days", "참여 연인원", s.participantDays(), "명"),
                new Item("photos", "점검 사진", s.photos(), "장"), new Item("facilities", "점검 장소", s.facilitiesChecked(), "곳"),
                new Item("skipped", "건너뜀", s.skipped(), "곳"), new Item("not_visited", "미방문", s.notVisited(), "곳"),
                new Item("issues_new", "신규 사건", s.issuesNew(), "건"), new Item("repairs_done", "보수 완료", s.repairsDone(), "건"),
                new Item("issues_closed", "종결", s.issuesClosed(), "건"), new Item("repair_again", "재보수 요청", s.repairAgain(), "건"));
        String template = String.join("\n", items.stream().map(i -> i.label() + ": " + String.format("%,d", i.value()) + i.unit()).toList());
        String text = template;
        String source = "template";
        String note = null;
        try {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (Item i : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", i.key());
                m.put("label", i.label());
                m.put("value", i.value());
                m.put("unit", i.unit());
                payload.add(m);
            }
            JsonNode out = ai.reportNarrative(payload, "시청 시설 담당 부서");
            if (out.path("text").isString() && !out.path("text").asString().isBlank()) {
                text = out.path("text").asString();
                source = "llm".equals(out.path("source").asString()) ? "llm" : "template";
                note = out.path("note").isString() ? out.path("note").asString() : null;
            }
        } catch (RuntimeException e) {
            log.warn("보고 문장 AI 실패: {}", e.getClass().getSimpleName());
            note = "AI에 연결하지 못해 고정 문장을 썼습니다.";
        }
        Instant now = clock.instant();
        String finalText = text;
        String finalSource = source;
        UUID program = programOf(user);
        Map<String, Object> saved = tx.execute(st -> {
            UUID agency = jdbc.queryForObject("""
                    SELECT organization_id FROM user_role_scopes WHERE user_id = ? AND role = 'PROGRAM_MANAGER' AND revoked_at IS NULL ORDER BY granted_at LIMIT 1""",
                    UUID.class, user.userId());
            Integer rev = jdbc.queryForObject("SELECT coalesce(max(revision_no), 0) + 1 FROM report_jobs WHERE organization_id = ? AND week_start = ?",
                    Integer.class, agency, java.sql.Date.valueOf(r.from()));
            UUID id = jdbc.queryForObject("""
                    INSERT INTO report_jobs (program_id, organization_id, week_start, status, requested_by, metrics_json, narrative, narrative_source,
                                             generated_at, source_cutoff_at, revision_no)
                    VALUES (?, ?, ?, 'SUCCEEDED', ?, ?::jsonb, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, program, agency, java.sql.Date.valueOf(r.from()),
                    user.userId(), json.writeValueAsString(Map.of("from", r.from().toString(), "to", r.to().toString(), "items", items)), finalText,
                    finalSource, Timestamp.from(now), Timestamp.from(now), rev);
            audit.record(program, user.userId(), "REPORT_GENERATED", "REPORT_JOB", id, null, null, Map.of("from", r.from().toString(), "to",
                    r.to().toString(), "source", finalSource));
            return Map.of("id", id, "rev", rev);
        });
        return new Narrative((UUID) saved.get("id"), r.from(), r.to(), items, text, source, note, now, (Integer) saved.get("rev"));
    }

    /** 엑셀 내려받기 기록(OPS-09). 엑셀은 화면이 집계값으로 만들고, 서버는 누가 어떤 기간을 내려받았는지 감사 이력에 남긴다. */
    public void recordExport(AuthenticatedUser user, LocalDate from, LocalDate to, List<String> sheets) {
        StatsService.Report r = stats.report(user, from, to);
        UUID program = programOf(user);
        tx.executeWithoutResult(st -> audit.record(program, user.userId(), "EXPORT_DOWNLOADED", "EXPORT", UUID.randomUUID(), null, null,
                Map.of("from", r.from().toString(), "to", r.to().toString(), "sheets", sheets == null ? List.of() : sheets)));
    }

    private UUID programOf(AuthenticatedUser user) {
        return jdbc.queryForObject("""
                SELECT program_id FROM user_role_scopes WHERE user_id = ? AND role = 'PROGRAM_MANAGER' AND revoked_at IS NULL ORDER BY granted_at LIMIT 1""",
                UUID.class, user.userId());
    }
}
