package kr.iroum.inspection.auth.otp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 인증번호 운영값. 기본값은 D-20 확정값이며 테스트에서만 기간을 줄여 같은 코드 경로를 검증한다. */
@ConfigurationProperties("inspection.otp")
public record OtpProperties(
        Duration codeTtl,
        Duration resendInterval,
        int maxFailures,
        Duration failureBlock,
        Duration sendWindow,
        int sendsPerPhone,
        int sendsPerIp) {

    public OtpProperties {
        codeTtl = codeTtl == null ? Duration.ofMinutes(10) : codeTtl;
        resendInterval = resendInterval == null ? Duration.ofSeconds(30) : resendInterval;
        maxFailures = maxFailures == 0 ? 5 : maxFailures;
        failureBlock = failureBlock == null ? Duration.ofMinutes(10) : failureBlock;
        sendWindow = sendWindow == null ? Duration.ofMinutes(10) : sendWindow;
        sendsPerPhone = sendsPerPhone == 0 ? 5 : sendsPerPhone;
        sendsPerIp = sendsPerIp == 0 ? 20 : sendsPerIp;
    }
}
