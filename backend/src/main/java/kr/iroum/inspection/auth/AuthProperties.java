package kr.iroum.inspection.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 운영값. 기간은 D-15·D-23 확정값이 기본이다. 서명 키는 환경 변수로만 받는다.
 *
 * @param jwtSecret Base64로 인코딩한 HS256 서명 키(32바이트 이상)
 * @param cookieSecure 인증 쿠키 Secure 속성. __Host- 접두사 쿠키는 Secure가 필수라 운영에서 끄지 않는다
 */
@ConfigurationProperties("inspection.auth")
public record AuthProperties(
        String jwtSecret,
        String issuer,
        String audience,
        Duration accessTokenTtl,
        Duration seniorSessionTtl,
        Duration adminSessionTtl,
        Duration restrictedSessionTtl,
        boolean cookieSecure) {

    public AuthProperties {
        issuer = issuer == null ? "itda-inspection" : issuer;
        audience = audience == null ? "itda-inspection-api" : audience;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        seniorSessionTtl = seniorSessionTtl == null ? Duration.ofDays(7) : seniorSessionTtl;
        adminSessionTtl = adminSessionTtl == null ? Duration.ofHours(12) : adminSessionTtl;
        restrictedSessionTtl = restrictedSessionTtl == null ? Duration.ofMinutes(10) : restrictedSessionTtl;
    }
}
