package kr.iroum.inspection.common.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 허용 Origin 목록. 기본값은 비어 있어 교차 출처 요청을 허용하지 않는다. */
@ConfigurationProperties("inspection.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
