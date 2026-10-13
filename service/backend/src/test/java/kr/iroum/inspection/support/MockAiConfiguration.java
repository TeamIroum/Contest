package kr.iroum.inspection.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * 계약 기반 mock AI 서버와 테스트 전용 사진 저장소·multipart 임시 경로. 실제 AI 서버 연결 검증이 아니다.
 * multipart 임시 경로를 따로 두어, 서버 검사 전 사진이 디스크에 쓰이지 않는지 테스트가 확인할 수 있게 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockAiConfiguration {

    public static final String API_KEY = "test-ai-key";

    @Bean(destroyMethod = "close")
    MockAiServer mockAiServer() {
        return new MockAiServer();
    }

    @Bean
    TestPaths testPaths() throws IOException {
        return new TestPaths(Files.createTempDirectory("itda-media"), Files.createTempDirectory("itda-multipart"));
    }

    @Bean
    DynamicPropertyRegistrar mockAiProperties(MockAiServer ai, TestPaths paths) {
        return registry -> {
            registry.add("inspection.ai.base-url", ai::baseUrl);
            registry.add("inspection.ai.api-key", () -> API_KEY);
            registry.add("inspection.ai.request-timeout", () -> "3s");
            // 개인정보 검사 불가 시 재시도 안내 값(고정 30초가 아니라 설정값임을 테스트에서 확인한다)
            registry.add("inspection.privacy-mask.retry-after", () -> "7s");
            registry.add("inspection.media.storage-root", () -> paths.media().toString());
            registry.add("spring.servlet.multipart.location", () -> paths.multipart().toString());
        };
    }

    public record TestPaths(Path media, Path multipart) {
    }
}
