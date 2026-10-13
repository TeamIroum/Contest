package kr.iroum.inspection.auth.session;

import java.util.Set;
import java.util.UUID;

/** 요청마다 세션·계정 상태를 확인한 뒤 만드는 인증 주체. 역할은 DB의 현재 값이다. */
public record AuthenticatedUser(UUID userId, UUID sessionId, String name, Set<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
