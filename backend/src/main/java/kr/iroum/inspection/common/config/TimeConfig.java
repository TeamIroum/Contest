package kr.iroum.inspection.common.config;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각 기준. 저장·응답 시각은 UTC, 활동일·사진 접수 기한은 Asia/Seoul 기준이다(업무 API 2절, D-17).
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /** 활동일과 기한 계산에 쓰는 업무 시간대. */
    public static final ZoneId ACTIVITY_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    Clock clock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
