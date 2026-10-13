package kr.iroum.inspection.review;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 업무 문제 코드 사전(업무 API 9.1·9.2절 "업무 사전에서 검증할 문제 코드", "허용 taxonomy 조합").
 * 시설 종류별 허용 문제 코드와 표시 이름. 출처는 AI taxonomy 사본(resources/taxonomy/defect-codes.json)이며 AI 지원 5종만 코드가 있다.
 * 추가 시설 3종은 업무 사전이 정해지지 않아 코드가 없다(문제 코드가 필요한 판정·정정은 거절, 결정 필요 항목).
 */
@Component
public class DefectDictionary {

    private final Map<String, Map<String, String>> codes;

    public DefectDictionary(JsonMapper json) {
        try (InputStream in = new ClassPathResource("taxonomy/defect-codes.json").getInputStream()) {
            JsonNode root = json.readTree(in);
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> facility : root.get("facilities").properties()) {
                Map<String, String> issues = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> issue : facility.getValue().properties()) {
                    issues.put(issue.getKey(), issue.getValue().asString());
                }
                out.put(facility.getKey(), Collections.unmodifiableMap(issues));
            }
            this.codes = Collections.unmodifiableMap(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean allows(String facilityType, String code) {
        return codes.getOrDefault(facilityType, Map.of()).containsKey(code);
    }

    public String label(String facilityType, String code) {
        return codes.getOrDefault(facilityType, Map.of()).getOrDefault(code, code);
    }

    public boolean known(String facilityType) {
        return codes.containsKey(facilityType);
    }
}
