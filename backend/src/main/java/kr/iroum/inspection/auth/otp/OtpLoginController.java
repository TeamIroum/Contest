package kr.iroum.inspection.auth.otp;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.auth.account.AccountRepository;
import kr.iroum.inspection.auth.demo.DemoProperties;
import kr.iroum.inspection.auth.otp.OtpChallengeService.Challenge;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.IssuedTokens;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.auth.web.AuthCookies;
import kr.iroum.inspection.auth.web.TokenResponse;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse;

/**
 * 어르신 인증번호 로그인(업무 API 4.2절, D-20).
 * <ul>
 * <li>SMS 경로는 발송 공급자(SmsSender)가 등록돼야 열린다. 없으면 503.</li>
 * <li>시연 경로는 시연 설정과 고정 코드가 있을 때만 열린다. 아니면 404. 고정 코드는 시연 시니어 계정에만 적용한다.</li>
 * <li>요청 응답은 등록 여부와 무관하게 같은 형태이며 미등록 번호에는 발송하지 않는다.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class OtpLoginController {

    private static final String SMS = "SMS";
    private static final String DEMO = "DEMO";

    public record OtpRequest(@NotBlank String phoneNumber) {
    }

    public record OtpVerification(@NotNull UUID challengeId, @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
            @NotBlank String clientId, @NotBlank String deviceLabel) {
    }

    private final OtpChallengeService challenges;
    private final AccountRepository accounts;
    private final PhoneCrypto crypto;
    private final SessionService sessions;
    private final AuthCookies cookies;
    private final DemoProperties demo;
    private final ObjectProvider<SmsSender> smsSender;

    public OtpLoginController(OtpChallengeService challenges, AccountRepository accounts, PhoneCrypto crypto,
            SessionService sessions, AuthCookies cookies, DemoProperties demo, ObjectProvider<SmsSender> smsSender) {
        this.challenges = challenges;
        this.accounts = accounts;
        this.crypto = crypto;
        this.sessions = sessions;
        this.cookies = cookies;
        this.demo = demo;
        this.smsSender = smsSender;
    }

    @PostMapping("/sms-challenges")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Challenge requestSms(@Valid @RequestBody OtpRequest body, HttpServletRequest request) {
        SmsSender sender = requireSms();
        String phone = normalize(body.phoneNumber());
        byte[] hmac = crypto.lookupHmac(phone);
        AccountRepository.PhoneAccount account = accounts.findSeniorByPhoneHmac(hmac).orElse(null);
        return challenges.request(hex(hmac), account == null ? null : account.id(), account == null ? 0 : account.authVersion(), SMS, null, request.getRemoteAddr(), code -> sender.sendCode(phone, code));
    }

    @PostMapping("/sms-verifications")
    TokenResponse verifySms(@Valid @RequestBody OtpVerification body, HttpServletResponse response) {
        requireSms();
        return login(challenges.verify(body.challengeId(), body.code(), SMS), body, response);
    }

    @PostMapping("/demo/otp-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Challenge requestDemo(@Valid @RequestBody OtpRequest body, HttpServletRequest request) {
        requireDemo();
        String phone = normalize(body.phoneNumber());
        byte[] hmac = crypto.lookupHmac(phone);
        // 고정 시연 코드는 시연 시니어 계정에만 적용한다. 그 밖의 번호는 미등록과 같게 처리한다.
        Optional<AccountRepository.PhoneAccount> account = accounts.findSeniorByPhoneHmac(hmac).filter(a -> demo.isDemoSenior(a.id()));
        Consumer<String> noDelivery = code -> {
        };
        return challenges.request(hex(hmac), account.map(AccountRepository.PhoneAccount::id).orElse(null),
                account.map(AccountRepository.PhoneAccount::authVersion).orElse(0L), DEMO, demo.otpCode(), request.getRemoteAddr(), noDelivery);
    }

    @PostMapping("/demo/otp-verifications")
    TokenResponse verifyDemo(@Valid @RequestBody OtpVerification body, HttpServletResponse response) {
        requireDemo();
        OtpChallengeService.Verified verified = challenges.verify(body.challengeId(), body.code(), DEMO);
        if (!demo.isDemoSenior(verified.userId())) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, "인증번호를 다시 확인해 주세요.");
        }
        return login(verified, body, response);
    }

    private TokenResponse login(OtpChallengeService.Verified verified, OtpVerification body, HttpServletResponse response) {
        IssuedTokens tokens = sessions.issue(verified.userId(), verified.authVersion(), body.clientId(), body.deviceLabel());
        cookies.setRefresh(response, tokens.refreshToken(), tokens.refreshTokenExpiresAt());
        return TokenResponse.of(tokens);
    }

    private SmsSender requireSms() {
        SmsSender sender = smsSender.getIfAvailable();
        if (sender == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        return sender;
    }

    private void requireDemo() {
        if (!demo.otpAvailable()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
        }
    }

    private static String normalize(String raw) {
        String phone = PhoneCrypto.normalize(raw);
        if (phone == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null,
                    List.of(new ErrorResponse.FieldError("phoneNumber", "INVALID_FORMAT", "휴대전화 번호 형식이 아닙니다.")));
        }
        return phone;
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
