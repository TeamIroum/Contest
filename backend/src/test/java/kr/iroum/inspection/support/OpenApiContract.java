package kr.iroum.inspection.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * 실제 응답 본문을 계약(OpenAPI)의 components.schemas로 검증한다(계약과 구현 대조).
 * 계약은 테스트 자원 contract/openapi.yaml에서 읽는다. 원본은 docs/openapi.yaml이며 ContractAssetSyncTest가 사본 일치를 확인한다.
 */
public final class OpenApiContract {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode DOCUMENT = load();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private OpenApiContract() {
    }

    public static void assertMatches(String schemaName, String body) {
        try {
            ObjectNode root = DOCUMENT.deepCopy();
            root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            root.put("$ref", "#/components/schemas/" + schemaName);
            JsonSchema schema = FACTORY.getSchema(root, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
            Set<ValidationMessage> errors = schema.validate(JSON.readTree(body));
            assertThat(errors).as(schemaName + " 계약 위반: " + errors.stream().map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; ")) + "\n본문: " + body).isEmpty();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 문서 안 JSON Pointer(예: #/paths/~1patrols~1{patrolId}/get/responses/200/content/application~1json/schema)의 스키마로 검증한다. */
    public static java.util.List<String> violations(String pointer, JsonNode body) {
        ObjectNode root = DOCUMENT.deepCopy();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", pointer);
        JsonSchema schema = FACTORY.getSchema(root, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        return schema.validate(body).stream().map(ValidationMessage::getMessage).toList();
    }

    public static JsonNode document() {
        return DOCUMENT;
    }

    /** 계약 테스트 자산(클래스패스 contract/ 아래, 예: "openapi.yaml", "examples/01-auth/csrf.200.json")의 내용. */
    public static String asset(String relative) {
        try (java.io.InputStream in = OpenApiContract.class.getResourceAsStream("/contract/" + relative)) {
            if (in == null) {
                throw new IllegalStateException("계약 테스트 자산 없음: contract/" + relative + " (./gradlew syncContractTestAssets)");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("계약 테스트 자산을 읽지 못했습니다: contract/" + relative, e);
        }
    }

    private static JsonNode load() {
        try {
            return new ObjectMapper(new YAMLFactory()).readTree(asset("openapi.yaml"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("contract/openapi.yaml을 읽지 못했습니다", e);
        }
    }
}
