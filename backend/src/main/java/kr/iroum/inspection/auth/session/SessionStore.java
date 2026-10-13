package kr.iroum.inspection.auth.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 로그인 세션·Refresh 해시 저장소(Redis). 세션 키의 만료 시각은 최초 로그인의 절대 만료로 고정하며 회전으로 늘리지 않는다(D-15).
 * Refresh 회전과 재사용 탐지는 Lua 스크립트 하나로 원자 처리한다.
 */
@Component
public class SessionStore {

    private static final String SESSION = "auth:session:";
    private static final String REFRESH = "auth:refresh:";
    private static final String USER_INDEX = "auth:user-sessions:";

    /** 결과: ROTATED / REUSED(이전 토큰 재사용 → 세션 종료) / REVOKED / INVALID. */
    private static final DefaultRedisScript<List> ROTATE = new DefaultRedisScript<>("""
            local sid = redis.call('GET', KEYS[1])
            if not sid then return {'INVALID', ''} end
            local key = 'auth:session:' .. sid
            if redis.call('EXISTS', key) == 0 then return {'INVALID', sid} end
            if redis.call('HGET', key, 'revoked') == '1' then return {'REVOKED', sid} end
            if redis.call('HGET', key, 'refreshHash') ~= ARGV[1] then
              redis.call('HSET', key, 'revoked', '1')
              return {'REUSED', sid}
            end
            redis.call('HSET', key, 'refreshHash', ARGV[2], 'lastUsedAt', ARGV[3])
            redis.call('HINCRBY', key, 'rotationGeneration', 1)
            local ttl = redis.call('PTTL', key)
            redis.call('SET', 'auth:refresh:' .. ARGV[2], sid, 'PX', ttl)
            return {'ROTATED', sid}
            """, List.class);

    public enum RotationResult { ROTATED, REUSED, REVOKED, INVALID }

    public record Rotation(RotationResult result, UUID sessionId) {
    }

    private final StringRedisTemplate redis;

    public SessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 세션을 저장한다. 키 만료는 앱 시계로 계산한 남은 기간(상대 TTL)으로 걸어 Redis 서버와의 시계 차이에 흔들리지 않게 한다.
     * 절대 만료 판단의 권위값은 absoluteExpiresAt 필드이며 SessionService가 매번 확인한다.
     */
    public void create(LoginSession session, String refreshHash, Duration ttl) {
        String key = SESSION + session.id();
        Map<String, String> fields = new HashMap<>();
        fields.put("userId", session.userId().toString());
        fields.put("authVersion", Long.toString(session.authVersion()));
        fields.put("issuedAt", session.issuedAt().toString());
        fields.put("absoluteExpiresAt", session.absoluteExpiresAt().toString());
        fields.put("deviceLabel", session.deviceLabel());
        fields.put("clientId", session.clientId());
        fields.put("lastUsedAt", session.lastUsedAt().toString());
        fields.put("rotationGeneration", "0");
        fields.put("revoked", "0");
        fields.put("refreshHash", refreshHash);
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, ttl);
        redis.opsForValue().set(REFRESH + refreshHash, session.id().toString(), ttl);
        redis.opsForSet().add(USER_INDEX + session.userId(), session.id().toString());
    }

    public Rotation rotate(String presentedHash, String newHash, Instant now) {
        List<?> result = redis.execute(ROTATE, List.of(REFRESH + presentedHash), presentedHash, newHash, now.toString());
        RotationResult kind = RotationResult.valueOf(String.valueOf(result.get(0)));
        String sid = String.valueOf(result.get(1));
        return new Rotation(kind, sid.isEmpty() ? null : UUID.fromString(sid));
    }

    public Optional<UUID> sessionIdForRefresh(String refreshHash) {
        String sid = redis.opsForValue().get(REFRESH + refreshHash);
        return sid == null ? Optional.empty() : Optional.of(UUID.fromString(sid));
    }

    public Optional<LoginSession> find(UUID sessionId) {
        Map<Object, Object> f = redis.opsForHash().entries(SESSION + sessionId);
        if (f.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LoginSession(sessionId, UUID.fromString(str(f, "userId")), Long.parseLong(str(f, "authVersion")),
                Instant.parse(str(f, "issuedAt")), Instant.parse(str(f, "absoluteExpiresAt")), str(f, "deviceLabel"),
                str(f, "clientId"), Instant.parse(str(f, "lastUsedAt")), Long.parseLong(str(f, "rotationGeneration")),
                "1".equals(str(f, "revoked"))));
    }

    public void touch(UUID sessionId, Instant now) {
        redis.opsForHash().put(SESSION + sessionId, "lastUsedAt", now.toString());
    }

    /** 세션을 종료로 표시한다. 재사용 탐지를 위해 만료 시각까지 키를 남긴다. */
    public void revoke(UUID sessionId) {
        String key = SESSION + sessionId;
        Object user = redis.opsForHash().get(key, "userId");
        if (user != null) {
            redis.opsForHash().put(key, "revoked", "1");
            redis.opsForSet().remove(USER_INDEX + user, sessionId.toString());
        }
    }

    /** 사용자의 유효 세션 목록. 만료·종료된 항목은 색인에서 정리한다. */
    public List<LoginSession> listActive(UUID userId) {
        Set<String> ids = redis.opsForSet().members(USER_INDEX + userId);
        List<LoginSession> sessions = new ArrayList<>();
        if (ids == null) {
            return sessions;
        }
        for (String id : ids) {
            Optional<LoginSession> session = find(UUID.fromString(id));
            if (session.isEmpty() || session.get().revoked()) {
                redis.opsForSet().remove(USER_INDEX + userId, id);
            } else {
                sessions.add(session.get());
            }
        }
        return sessions;
    }

    private static String str(Map<Object, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? null : value.toString();
    }
}
