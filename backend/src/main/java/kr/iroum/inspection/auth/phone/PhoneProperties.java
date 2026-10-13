package kr.iroum.inspection.auth.phone;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 전화번호 암호화(AES-256-GCM)·검색용 HMAC 키. Base64 32바이트, 환경 변수로만 받는다. */
@ConfigurationProperties("inspection.phone")
public record PhoneProperties(String encryptionKey, String hmacKey) {
}
