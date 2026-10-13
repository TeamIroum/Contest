package kr.iroum.inspection.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 판독 결과 스냅샷(analysis_results, DB 설계 7절). 추가만 하며 같은 작업의 직전 스냅샷과 결과 해시가 같으면 다시 넣지 않는다.
 * 원문에서 사진 주소만 빼고 보존한다(등급 등은 내부 보존, 사용자 응답에 쓰지 않음). 호출자 트랜잭션 안에서 쓴다.
 */
@Component
public class AnalysisSnapshots {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public AnalysisSnapshots(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 새 스냅샷을 넣었으면 true(같은 결과의 재전달이면 false). */
    public boolean append(UUID programId, UUID jobId, JsonNode result, UUID imageMediaId, Instant observedAt) {
        String text = json.writeValueAsString(AnalyzeOutcome.snapshot(result));
        byte[] hash = sha256(text);
        jdbc.queryForList("SELECT id FROM analysis_jobs WHERE id = ? FOR UPDATE", jobId);
        byte[] last = jdbc.query("SELECT result_hash FROM analysis_results WHERE analysis_job_id = ? ORDER BY snapshot_no DESC LIMIT 1",
                (rs, i) -> rs.getBytes(1), jobId).stream().findFirst().orElse(null);
        if (last != null && MessageDigest.isEqual(last, hash)) {
            return false;
        }
        JsonNode meta = result.path("meta");
        jdbc.update("""
                INSERT INTO analysis_results (program_id, analysis_job_id, snapshot_no, result_hash, result_json, model_version, prompt_version,
                    contract_version, observed_at, image_media_id)
                VALUES (?, ?, (SELECT COALESCE(max(snapshot_no), 0) + 1 FROM analysis_results WHERE analysis_job_id = ?), ?, ?::jsonb, ?, ?, ?, ?, ?)""",
                programId, jobId, jobId, hash, text, text(meta, "model"), text(meta, "prompt_version"), text(meta, "contract_version"),
                Timestamp.from(observedAt), imageMediaId);
        return true;
    }

    public static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
