package kr.iroum.inspection.auth.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import kr.iroum.inspection.auth.session.IssuedTokens;

/** OpenAPI TokenResponse. Refresh 토큰 원문은 본문에 넣지 않는다. */
public record TokenResponse(String tokenType, String accessToken, Instant accessTokenExpiresAt, Instant refreshTokenExpiresAt,
        UUID sessionId, User user) {

    public record User(UUID id, String name, List<String> roles) {
    }

    public static TokenResponse of(IssuedTokens tokens) {
        return new TokenResponse("Bearer", tokens.accessToken(), tokens.accessTokenExpiresAt(), tokens.refreshTokenExpiresAt(),
                tokens.sessionId(), new User(tokens.userId(), tokens.name(), List.copyOf(tokens.roles())));
    }
}
