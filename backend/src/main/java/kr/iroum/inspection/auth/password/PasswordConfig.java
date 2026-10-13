package kr.iroum.inspection.auth.password;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/**
 * 비밀번호 해시. PBKDF2-HMAC-SHA256(Spring Security 5.8 기본값: 솔트 16바이트, 310,000회)을 쓴다.
 * BCrypt의 72바이트 제한이 D-22(최대 길이 없음)와 맞지 않아 택했다. 저장 형식에 알고리즘 ID를 붙여 나중에 바꿀 수 있게 한다.
 */
@Configuration
public class PasswordConfig {

    static final String ID = "pbkdf2@SpringSecurity_v5_8";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(ID, Map.of(ID, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}
