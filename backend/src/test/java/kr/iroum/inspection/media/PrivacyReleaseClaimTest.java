package kr.iroum.inspection.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** 전송 점유 기간 경계(PHOTO-13): 점유는 실제 HTTP 제한 시간보다 길어야 끊기기 전의 요청과 다음 요청이 겹치지 않는다. */
class PrivacyReleaseClaimTest {

    @Test
    void claimOutlastsTheLongerOfBothConfiguredTimeouts() {
        assertThat(PrivacyReleaseSender.claimFor(Duration.ofSeconds(30), Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(60));
        // AI 클라이언트 제한 시간이 더 길게 설정돼도 점유가 그보다 길다(이전에는 개인정보 전송 설정만 보고 60초였다).
        assertThat(PrivacyReleaseSender.claimFor(Duration.ofSeconds(30), Duration.ofSeconds(45))).isEqualTo(Duration.ofSeconds(90))
                .isGreaterThan(Duration.ofSeconds(45));
        assertThat(PrivacyReleaseSender.claimFor(Duration.ofSeconds(40), Duration.ofSeconds(3))).isEqualTo(Duration.ofSeconds(80));
    }
}
