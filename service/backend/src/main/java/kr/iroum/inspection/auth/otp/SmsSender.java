package kr.iroum.inspection.auth.otp;

/**
 * 실제 SMS 발송 경계. 공급자가 정해지면 구현체를 빈으로 등록한다(기능정의서 COM-02, 공급자 선정 필요).
 * 구현체가 없으면 /auth/sms-* 경로는 503으로 응답한다. 코드·번호를 로그에 남기지 않는다.
 */
public interface SmsSender {

    void sendCode(String normalizedPhone, String code);
}
