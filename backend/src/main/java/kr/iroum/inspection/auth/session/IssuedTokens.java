package kr.iroum.inspection.auth.session;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 로그인·갱신 결과. refreshToken은 쿠키로만 내보내고 응답 본문에는 넣지 않는다. */
public record IssuedTokens(String accessToken, Instant accessTokenExpiresAt, String refreshToken, Instant refreshTokenExpiresAt,
        UUID sessionId, UUID userId, String name, Set<String> roles) {
}
