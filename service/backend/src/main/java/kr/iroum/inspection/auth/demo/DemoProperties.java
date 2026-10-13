package kr.iroum.inspection.auth.demo;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시연 전용 설정(local·demo 프로필). 비밀 값(OTP 코드, 관리자 임시 비밀번호)은 환경 변수로만 받는다.
 *
 * @param enabled 시연 경로(/auth/demo/*)와 시연 계정 준비를 켠다. 운영에서는 false
 * @param otpCode 6자리 고정 시연 코드. 비어 있으면 시연 OTP 경로는 404
 * @param seniors 시연 시니어 계정과 가상 번호(010-0000-000x)
 * @param admins 시연 관리자 계정과 로그인 ID
 * @param adminTemporaryPassword 시연 관리자 임시 비밀번호(D-22 규칙). 첫 로그인에 바꾼다(D-23)
 * @param registeredSeniors service/: 관리자 웹에서 새로 등록한 어르신(ACT-01)도 고정 시연 코드로 로그인하게 한다.
 *        문자 발송 공급자가 없는 시연 PC 전용이며 기본값은 false다
 */
@ConfigurationProperties("inspection.demo")
public record DemoProperties(boolean enabled, String otpCode, List<Senior> seniors, List<Admin> admins,
        String adminTemporaryPassword, boolean registeredSeniors) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DemoProperties {
        seniors = seniors == null ? List.of() : List.copyOf(seniors);
        admins = admins == null ? List.of() : List.copyOf(admins);
    }

    public DemoProperties(boolean enabled, String otpCode, List<Senior> seniors, List<Admin> admins, String adminTemporaryPassword) {
        this(enabled, otpCode, seniors, admins, adminTemporaryPassword, false);
    }

    public record Senior(UUID userId, String phone) {
    }

    public record Admin(UUID userId, String loginId) {
    }

    public boolean otpAvailable() {
        return enabled && otpCode != null && otpCode.matches("[0-9]{6}");
    }

    public boolean isDemoSenior(UUID userId) {
        return registeredSeniors || seniors.stream().anyMatch(s -> s.userId().equals(userId));
    }
}
