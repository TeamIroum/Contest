package kr.iroum.inspection.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency-Key 처리(업무 API 2절, DB 설계 idempotency_requests).
 * <ul>
 * <li>(사용자, 동작, 키) 첫 요청은 행을 만들고 업무 처리 결과를 같은 트랜잭션에 COMPLETED로 기록한다.</li>
 * <li>같은 키·같은 요청 해시는 저장한 최초 결과를 재현하고, 다른 해시는 409 IDEMPOTENCY_CONFLICT다.</li>
 * <li>같은 키 동시 요청은 유니크 인덱스에서 앞선 트랜잭션의 커밋을 기다린 뒤 재현된다.</li>
 * <li>업무 오류로 롤백되면 행도 남지 않아 같은 키로 다시 시도할 수 있다.</li>
 * </ul>
 * 호출자 트랜잭션 안에서만 쓴다(MANDATORY). 결과 스냅샷에 비밀 값을 넣지 않는다.
 */
@Service
public class IdempotencyService {

    private static final Duration RETENTION = Duration.ofDays(7);

    public record Outcome<T>(int status, T body, boolean replayed) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Outcome<T> execute(UUID userId, String operation, UUID key, Object requestIdentity, Class<T> type, int status,
            Supplier<T> action) {
        if (key == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 헤더가 필요합니다.");
        }
        byte[] hash = hash(requestIdentity);
        Timestamp now = Timestamp.from(clock.instant());
        int inserted = jdbc.update("""
                INSERT INTO idempotency_requests (user_id, operation, idempotency_key, request_hash, state, created_at, expires_at)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, ?) ON CONFLICT (user_id, operation, idempotency_key) DO NOTHING""",
                userId, operation, key, hash, now, Timestamp.from(clock.instant().plus(RETENTION)));
        if (inserted == 0) {
            return replay(userId, operation, key, hash, type);
        }
        T body = action.get();
        jdbc.update("""
                UPDATE idempotency_requests SET state = 'COMPLETED', response_code = ?, result_snapshot_json = ?::jsonb
                WHERE user_id = ? AND operation = ? AND idempotency_key = ?""",
                status, json.writeValueAsString(body), userId, operation, key);
        return new Outcome<>(status, body, false);
    }

    /**
     * 외부 호출 전 재현 확인(트랜잭션 밖에서도 쓴다). 같은 키·같은 요청의 완료 결과가 있으면 그 결과, 없으면 빈 값.
     * 같은 키에 다른 요청이면 409 IDEMPOTENCY_CONFLICT. 처리 중이면 빈 값(뒤의 execute가 처리 중 충돌을 판단한다).
     */
    public <T> Optional<Outcome<T>> find(UUID userId, String operation, UUID key, Object requestIdentity, Class<T> type) {
        if (key == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 헤더가 필요합니다.");
        }
        Optional<Map<String, Object>> row = jdbc.queryForList("""
                SELECT request_hash, state FROM idempotency_requests WHERE user_id = ? AND operation = ? AND idempotency_key = ?""",
                userId, operation, key).stream().findFirst();
        if (row.isEmpty() || !"COMPLETED".equals(row.get().get("state"))) {
            if (row.isPresent() && !MessageDigest.isEqual((byte[]) row.get().get("request_hash"), hash(requestIdentity))) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "같은 요청 키로 다른 내용이 이미 처리됐습니다.");
            }
            return Optional.empty();
        }
        return Optional.of(replay(userId, operation, key, hash(requestIdentity), type));
    }

    private <T> Outcome<T> replay(UUID userId, String operation, UUID key, byte[] hash, Class<T> type) {
        Optional<Map<String, Object>> row = jdbc.queryForList("""
                SELECT request_hash, state, response_code, result_snapshot_json::text AS snapshot FROM idempotency_requests
                WHERE user_id = ? AND operation = ? AND idempotency_key = ?""", userId, operation, key).stream().findFirst();
        if (row.isEmpty() || !MessageDigest.isEqual((byte[]) row.get().get("request_hash"), hash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "같은 요청 키로 다른 내용이 이미 처리됐습니다.");
        }
        if (!"COMPLETED".equals(row.get().get("state"))) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "같은 요청을 처리하고 있습니다. 잠시 후 다시 확인해 주세요.");
        }
        T body = json.readValue((String) row.get().get("snapshot"), type);
        return new Outcome<>(((Number) row.get().get("response_code")).intValue(), body, true);
    }

    private byte[] hash(Object identity) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(identity).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
