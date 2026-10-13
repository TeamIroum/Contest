package kr.iroum.inspection.auth.session;

import java.util.stream.Collectors;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class AuthenticatedUserToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser user;

    public AuthenticatedUserToken(AuthenticatedUser user) {
        super(user.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).collect(Collectors.toSet()));
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return user;
    }
}
