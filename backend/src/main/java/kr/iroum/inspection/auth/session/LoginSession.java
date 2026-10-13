package kr.iroum.inspection.auth.session;

import java.time.Instant;
import java.util.UUID;

/** Redis에 보관하는 기기별 로그인 세션. Refresh 원문은 저장하지 않고 해시만 둔다. */
public record LoginSession(
        UUID id,
        UUID userId,
        long authVersion,
        Instant issuedAt,
        Instant absoluteExpiresAt,
        String deviceLabel,
        String clientId,
        Instant lastUsedAt,
        long rotationGeneration,
        boolean revoked) {
}
