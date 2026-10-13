package kr.iroum.inspection.support;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 활동일 2026-10-15(KST) 오전 10:30 = 01:30Z로 고정한 시계. 테스트가 set/advance로 옮긴다. */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    public static final Instant NOW = Instant.parse("2026-10-15T01:30:00Z");

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(NOW);
    }
}
