package kr.iroum.inspection.common.web;

import org.springframework.security.core.Authentication;

import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.auth.session.AuthenticatedUserToken;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/** 컨트롤러에서 세션 사용자를 꺼낸다. 요청 본문의 사용자 ID는 믿지 않는다. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthenticatedUser of(Authentication authentication) {
        if (authentication instanceof AuthenticatedUserToken token) {
            return token.getPrincipal();
        }
        throw new ApiException(ErrorCode.AUTH_REQUIRED, "다시 로그인해 주세요.");
    }
}
