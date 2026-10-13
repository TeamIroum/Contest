package kr.iroum.inspection.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업(검토 작업 outbox 등). 작업은 모두 멱등이며 SKIP LOCKED로 여러 서버가 겹쳐도 한 번만 처리한다.
 * inspection.jobs.scheduling-enabled=false면 끈다(테스트가 직접 부를 때).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "inspection.jobs.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
