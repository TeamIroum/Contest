package kr.iroum.inspection.auth.phone;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/**
 * 전화번호 정규화·암호화·검색용 HMAC(DB 설계 3절). 평문 번호는 저장·로그하지 않는다.
 * 키가 없으면 전화 인증 경로만 503으로 막고 나머지 서버는 동작한다.
 */
@Component
public class PhoneCrypto {

    private static final Pattern MOBILE = Pattern.compile("01[016789][0-9]{7,8}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] encryptionKey;
    private final byte[] hmacKey;

    public PhoneCrypto(PhoneProperties properties) {
        this.encryptionKey = decode(properties.encryptionKey());
        this.hmacKey = decode(properties.hmacKey());
    }

    /** 숫자만 남겨 휴대전화 형식인지 확인한다. 형식이 아니면 null. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("82")) {
            digits = "0" + digits.substring(2);
        }
        return MOBILE.matcher(digits).matches() ? digits : null;
    }

    public byte[] lookupHmac(String normalized) {
        requireKeys();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] encrypt(String normalized) {
        requireKeys();
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(byte[] ciphertext) {
        requireKeys();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, ciphertext, 0, 12));
            return new String(cipher.doFinal(ciphertext, 12, ciphertext.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean configured() {
        return encryptionKey != null && hmacKey != null;
    }

    private void requireKeys() {
        if (!configured()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length != 32) {
            throw new IllegalStateException("전화번호 암호화·HMAC 키는 Base64 32바이트여야 합니다.");
        }
        return bytes;
    }
}
