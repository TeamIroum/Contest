package kr.iroum.inspection;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 테스트용 빈 PostgreSQL·Redis와 실행마다 새로 만드는 JWT 서명 키·전화번호 키. 로컬 compose와 같은 버전을 고정한다. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String POSTGRES_IMAGE = "postgres:16.15";
    public static final String REDIS_IMAGE = "redis:7.4";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);
    }

    @Bean
    DynamicPropertyRegistrar testAuthProperties() {
        String jwtSecret = randomKey();
        String phoneEncryptionKey = randomKey();
        String phoneHmacKey = randomKey();
        return registry -> {
            registry.add("inspection.auth.jwt-secret", () -> jwtSecret);
            registry.add("inspection.phone.encryption-key", () -> phoneEncryptionKey);
            registry.add("inspection.phone.hmac-key", () -> phoneHmacKey);
        };
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
