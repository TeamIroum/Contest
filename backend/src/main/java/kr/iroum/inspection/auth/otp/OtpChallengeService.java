package kr.iroum.inspection.auth.otp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/**
 * 인증번호 챌린지(D-20). SMS와 시연 OTP가 같은 규칙을 쓴다.
 * <ul>
 * <li>6자리, 최초 발급부터 10분 유효. 재발송은 30초 간격이며 같은 코드·만료를 유지한다.</li>
 * <li>전화번호·활성 챌린지 합산 오입력 5회면 챌린지를 무효화하고 번호를 10분 제한한다. 재발송으로 초기화하지 않는다.</li>
 * <li>발송(최초·재발송 합산)은 번호별 10분 5회, IP별 10분 20회.</li>
 * <li>미등록 번호도 같은 형태로 응답하지만 발송하지 않고, 검증은 항상 실패한다.</li>
 * </ul>
 * 검증과 오입력 집계는 Lua 스크립트로 원자 처리한다. 검증용 코드는 해시로 두고, 같은 코드 재발송에 필요한 값만
 * 전화번호 암호화 키로 암호화해 챌린지 수명(10분) 동안 Redis에 둔다.
 */
@Service
public class OtpChallengeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final DefaultRedisScript<List> VERIFY = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[3]) == 1 then return {'BLOCKED', redis.call('PTTL', KEYS[3])} end
            if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('HGET', KEYS[1], 'consumed') == '1' then return {'INVALID', 0} end
            if redis.call('HGET', KEYS[1], 'codeHash') == ARGV[1] then
              redis.call('HSET', KEYS[1], 'consumed', '1')
              redis.call('DEL', KEYS[2])
              return {'OK', 0}
            end
            local fails = redis.call('INCR', KEYS[2])
            if fails == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[4]) end
            if fails >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1])
              redis.call('DEL', KEYS[2])
              redis.call('SET', KEYS[3], '1', 'PX', ARGV[3])
              return {'BLOCKED', tonumber(ARGV[3])}
            end
            return {'WRONG', fails}
            """, List.class);

    public record Challenge(UUID challengeId, Instant expiresAt, Instant resendAvailableAt) {
    }

    /** authVersion: 인증번호를 요청한 시점의 계정 auth_version. 세션 발급에 그대로 넘긴다. */
    public record Verified(UUID userId, long authVersion) {
    }

    private final StringRedisTemplate redis;
    private final OtpProperties properties;
    private final PhoneCrypto crypto;
    private final Clock clock;

    public OtpChallengeService(StringRedisTemplate redis, OtpProperties properties, PhoneCrypto crypto, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.crypto = crypto;
        this.clock = clock;
    }

    /**
     * 챌린지를 발급하거나 재발송한다.
     *
     * @param phoneKey 정규화 번호의 HMAC(16진수). 원 번호는 Redis에 두지 않는다
     * @param userId 등록·활성 계정이면 그 ID, 아니면 null(발송하지 않음)
     * @param authVersion userId가 있을 때 요청 시점의 auth_version. 그 사이 계정이 바뀌면 이 챌린지로 로그인할 수 없다
     * @param channel SMS 또는 DEMO. 채널이 다르면 서로 다른 챌린지다
     * @param fixedCode 시연 고정 코드. SMS는 null(임의 생성)
     * @param sender 등록 번호일 때만 호출되는 발송 함수(코드 전달)
     */
    public Challenge request(String phoneKey, UUID userId, long authVersion, String channel, String fixedCode, String clientIp, Consumer<String> sender) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String lock = "otp:lock:" + channel + ":" + phoneKey;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, "1", Duration.ofSeconds(5)))) {
            throw rateLimited(Duration.ofSeconds(1));
        }
        try {
            blockedFor(phoneKey).ifPresent(left -> {
                throw rateLimited(left);
            });
            String activeKey = "otp:active:" + channel + ":" + phoneKey;
            String activeId = redis.opsForValue().get(activeKey);
            Map<Object, Object> active = activeId == null ? Map.of() : redis.opsForHash().entries(challengeKey(activeId));
            boolean reusable = !active.isEmpty() && !"1".equals(active.get("consumed"));
            if (reusable) {
                Instant resendAt = Instant.parse((String) active.get("resendAvailableAt"));
                if (now.isBefore(resendAt)) {
                    throw rateLimited(Duration.between(now, resendAt));
                }
            }
            countSend("otp:send:phone:" + phoneKey, properties.sendsPerPhone(), now);
            countSend("otp:send:ip:" + clientIp, properties.sendsPerIp(), now);
            Instant resendAvailableAt = now.plus(properties.resendInterval());
            if (reusable) {
                // 재발송: 같은 챌린지·코드·만료를 유지하고 재발송 가능 시각만 바꾼다.
                redis.opsForHash().put(challengeKey(activeId), "resendAvailableAt", resendAvailableAt.toString());
                if (userId != null) {
                    sender.accept(crypto.decrypt(java.util.Base64.getDecoder().decode((String) active.get("codeForResend"))));
                }
                return new Challenge(UUID.fromString(activeId), Instant.parse((String) active.get("expiresAt")), resendAvailableAt);
            }
            String code = fixedCode != null ? fixedCode : String.format("%06d", RANDOM.nextInt(1_000_000));
            UUID challengeId = UUID.randomUUID();
            Instant expiresAt = now.plus(properties.codeTtl());
            Map<String, String> fields = new java.util.HashMap<>();
            fields.put("phoneKey", phoneKey);
            fields.put("channel", channel);
            fields.put("userId", userId == null ? "" : userId.toString());
            fields.put("authVersion", Long.toString(authVersion));
            fields.put("codeHash", hash(code));
            // 재발송 때 같은 코드를 다시 보내기 위해 암호화해 챌린지 수명 동안만 보관한다. 로그·응답에는 쓰지 않는다.
            fields.put("codeForResend", java.util.Base64.getEncoder().encodeToString(crypto.encrypt(code)));
            fields.put("expiresAt", expiresAt.toString());
            fields.put("resendAvailableAt", resendAvailableAt.toString());
            fields.put("consumed", "0");
            redis.opsForHash().putAll(challengeKey(challengeId.toString()), fields);
            redis.expire(challengeKey(challengeId.toString()), properties.codeTtl());
            redis.opsForValue().set(activeKey, challengeId.toString(), properties.codeTtl());
            if (userId != null) {
                sender.accept(code);
            }
            return new Challenge(challengeId, expiresAt, resendAvailableAt);
        } finally {
            redis.delete(lock);
        }
    }

    /** 코드를 확인한다. 성공하면 챌린지를 소진하고 계정 ID를 돌려준다. */
    public Verified verify(UUID challengeId, String code, String channel) {
        Map<Object, Object> challenge = redis.opsForHash().entries(challengeKey(challengeId.toString()));
        if (challenge.isEmpty() || !channel.equals(challenge.get("channel"))) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, "인증번호를 다시 확인해 주세요.");
        }
        String phoneKey = (String) challenge.get("phoneKey");
        List<?> result = redis.execute(VERIFY,
                List.of(challengeKey(challengeId.toString()), "otp:fail:" + phoneKey, "otp:block:" + phoneKey),
                hash(code), Integer.toString(properties.maxFailures()), Long.toString(properties.failureBlock().toMillis()),
                Long.toString(properties.codeTtl().toMillis()));
        String status = String.valueOf(result.get(0));
        switch (status) {
            case "OK" -> {
                String userId = (String) challenge.get("userId");
                if (userId == null || userId.isEmpty()) {
                    throw new ApiException(ErrorCode.AUTH_REQUIRED, "인증번호를 다시 확인해 주세요.");
                }
                return new Verified(UUID.fromString(userId), Long.parseLong((String) challenge.get("authVersion")));
            }
            case "BLOCKED" -> throw rateLimited(Duration.ofMillis(((Number) result.get(1)).longValue()));
            default -> throw new ApiException(ErrorCode.AUTH_REQUIRED, "인증번호를 다시 확인해 주세요.");
        }
    }

    private Optional<Duration> blockedFor(String phoneKey) {
        Long ttl = redis.getExpire("otp:block:" + phoneKey, java.util.concurrent.TimeUnit.MILLISECONDS);
        return ttl != null && ttl > 0 ? Optional.of(Duration.ofMillis(ttl)) : Optional.empty();
    }

    /** 연속 10분 창 발송 횟수(슬라이딩 창). 넘으면 가장 오래된 발송이 창을 벗어날 때까지 429. */
    private void countSend(String key, int limit, Instant now) {
        long nowMs = now.toEpochMilli();
        long windowMs = properties.sendWindow().toMillis();
        ZSetOperations<String, String> zset = redis.opsForZSet();
        zset.removeRangeByScore(key, 0, nowMs - windowMs);
        Long count = zset.zCard(key);
        if (count != null && count >= limit) {
            var oldest = zset.rangeWithScores(key, 0, 0);
            long oldestMs = oldest == null || oldest.isEmpty() ? nowMs : oldest.iterator().next().getScore().longValue();
            throw rateLimited(Duration.ofMillis(Math.max(1000, oldestMs + windowMs - nowMs)));
        }
        zset.add(key, UUID.randomUUID().toString(), nowMs);
        redis.expire(key, properties.sendWindow());
    }

    private static ApiException rateLimited(Duration retryAfter) {
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        return new ApiException(ErrorCode.RATE_LIMITED, "잠시 후 다시 시도해 주세요.", Map.of("retryAfterSeconds", seconds));
    }

    private static String challengeKey(String id) {
        return "otp:challenge:" + id;
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
