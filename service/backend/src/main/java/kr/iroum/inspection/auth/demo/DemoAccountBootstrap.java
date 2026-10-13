package kr.iroum.inspection.auth.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.password.PasswordPolicy;
import kr.iroum.inspection.auth.phone.PhoneCrypto;

/**
 * 시연 설정이 켜졌을 때 seed 계정에 로그인 수단을 준비한다(사진 보관과 인증 정책 5절). 운영(시연 설정 꺼짐)에서는 아무것도 하지 않는다.
 * <ul>
 * <li>시니어: 가상 전화번호(010-0000-xxxx)를 암호문·HMAC으로 넣는다.</li>
 * <li>관리자: 환경 변수의 임시 비밀번호(D-22 규칙)로 로그인 ID·해시를 넣고 passwordChangeRequired=true로 둔다(D-23).</li>
 * </ul>
 * 이미 로그인 수단이 있는 계정은 덮어쓰지 않는다.
 */
@Component
public class DemoAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountBootstrap.class);

    private final DemoProperties demo;
    private final PhoneCrypto crypto;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public DemoAccountBootstrap(DemoProperties demo, PhoneCrypto crypto, JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.demo = demo;
        this.crypto = crypto;
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!demo.enabled()) {
            return;
        }
        prepareAdmins();
        prepareSeniors();
    }

    private void prepareAdmins() {
        if (demo.admins().isEmpty()) {
            return;
        }
        String password = demo.adminTemporaryPassword();
        if (password == null || password.isEmpty()) {
            log.info("DEMO_ADMIN_TEMP_PASSWORD가 없어 관리자 시연 계정을 준비하지 않습니다.");
            return;
        }
        if (!PasswordPolicy.satisfiedBy(password)) {
            throw new IllegalStateException("DEMO_ADMIN_TEMP_PASSWORD가 비밀번호 규칙(D-22: 8자 이상, 영문·숫자·기호)을 만족하지 않습니다.");
        }
        int prepared = 0;
        for (DemoProperties.Admin admin : demo.admins()) {
            prepared += jdbc.update("""
                    UPDATE users SET login_id = ?, password_hash = ?, password_change_required = true, updated_at = now()
                    WHERE id = ? AND login_id IS NULL AND password_hash IS NULL
                      AND NOT EXISTS (SELECT 1 FROM users other WHERE other.login_id = ?)""",
                    admin.loginId(), encoder.encode(password), admin.userId(), admin.loginId());
        }
        log.info("관리자 시연 계정 준비: {}건 새로 설정(임시 비밀번호, 첫 로그인 때 변경)", prepared);
    }

    private void prepareSeniors() {
        if (demo.seniors().isEmpty()) {
            return;
        }
        if (!crypto.configured()) {
            log.warn("시연 설정이 켜졌지만 전화번호 키(PHONE_ENC_KEY, PHONE_HMAC_KEY)가 없어 시연 시니어 번호를 준비하지 않습니다.");
            return;
        }
        int prepared = 0;
        for (DemoProperties.Senior senior : demo.seniors()) {
            String phone = PhoneCrypto.normalize(senior.phone());
            if (phone == null || !phone.startsWith("0100000")) {
                // 실제 번호가 섞이지 않도록 가상 번호 대역만 허용한다.
                throw new IllegalStateException("시연 시니어 번호는 가상 번호(010-0000-xxxx)만 허용합니다: " + senior.userId());
            }
            prepared += jdbc.update("""
                    UPDATE users SET phone_ciphertext = ?, phone_lookup_hmac = ?, updated_at = now()
                    WHERE id = ? AND phone_ciphertext IS NULL""", crypto.encrypt(phone), crypto.lookupHmac(phone), senior.userId());
        }
        log.info("시연 시니어 번호 준비: {}건 새로 설정", prepared);
    }
}
