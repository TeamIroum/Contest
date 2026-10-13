package kr.iroum.inspection.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 계약(테스트 자산 contract/openapi.yaml)에 실린 모든 예시(요청·응답)를 그 동작의 스키마로 검증한다. 예시 JSON과 기계 판독 계약이
 * 어긋나지 않게 한다. 예시 파일(contract/examples, 원본 docs/examples)의 내용과 OpenAPI 안 예시가 같은지는 x-source-file로 대조한다.
 */
class OpenApiExamplesTest {

    @Test
    void everyEmbeddedExampleMatchesItsOperationSchema() throws Exception {
        JsonNode doc = OpenApiContract.document();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Iterator<Map.Entry<String, JsonNode>> paths = doc.get("paths").fields(); paths.hasNext();) {
            Map.Entry<String, JsonNode> path = paths.next();
            for (Iterator<Map.Entry<String, JsonNode>> ops = path.getValue().fields(); ops.hasNext();) {
                Map.Entry<String, JsonNode> op = ops.next();
                String base = "#/paths/" + escape(path.getKey()) + "/" + op.getKey();
                JsonNode body = op.getValue().path("requestBody").path("content").path("application/json");
                checked += check(body, base + "/requestBody/content/application~1json/schema", problems);
                for (Iterator<Map.Entry<String, JsonNode>> rs = op.getValue().path("responses").fields(); rs.hasNext();) {
                    Map.Entry<String, JsonNode> r = rs.next();
                    JsonNode content = r.getValue().path("content").path("application/json");
                    checked += check(content, base + "/responses/" + r.getKey() + "/content/application~1json/schema", problems);
                }
            }
        }
        assertThat(checked).as("검사한 예시 수").isGreaterThan(50);
        assertThat(problems).as(String.join("\n", problems)).isEmpty();
    }

    private static int check(JsonNode content, String pointer, List<String> problems) throws Exception {
        if (!content.has("schema") || !content.has("examples")) {
            return 0;
        }
        int n = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = content.get("examples").fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> ex = it.next();
            JsonNode value = ex.getValue().get("value");
            String source = ex.getValue().path("x-source-file").asText(null);
            if (source != null) {
                JsonNode file = new com.fasterxml.jackson.databind.ObjectMapper().readTree(OpenApiContract.asset(source));
                if (!file.equals(value)) {
                    problems.add(source + ": OpenAPI 안 예시와 파일 내용이 다름");
                }
            }
            for (String v : OpenApiContract.violations(pointer, value)) {
                problems.add(pointer + " [" + ex.getKey() + "]: " + v);
            }
            n++;
        }
        return n;
    }

    private static String escape(String path) {
        return path.replace("~", "~0").replace("/", "~1");
    }
}
