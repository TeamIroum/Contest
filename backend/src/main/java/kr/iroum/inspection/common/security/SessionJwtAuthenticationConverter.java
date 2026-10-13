package kr.iroum.inspection.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import kr.iroum.inspection.auth.session.AuthenticatedUserToken;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.common.error.ApiException;

/**
 * 서명 검증을 통과한 JWT를 세션·계정 상태로 다시 확인한다. Redis·DB 장애 시 보호 API를 허용하지 않는다(업무 API 4.1절).
 */
@Component
public class SessionJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final SessionService sessions;

    public SessionJwtAuthenticationConverter(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            return new AuthenticatedUserToken(sessions.authenticate(jwt));
        } catch (ApiException e) {
            throw new BadCredentialsException(e.getMessage());
        } catch (DataAccessException e) {
            throw new AuthenticationServiceException("인증 저장소에 연결할 수 없습니다.", e);
        }
    }
}
