package kr.iroum.inspection.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 계약 테스트 자산(src/test/resources/contract)이 원본 docs/openapi.yaml·docs/examples와 같은지 확인한다(개발 브랜치).
 * 문서를 싣지 않는 배포 브랜치에는 원본이 없어 건너뛴다. 이때 계약 검사는 사본만으로 그대로 실행된다.
 */
class ContractAssetSyncTest {

    private static final Path DOCS = Path.of("..", "docs");
    private static final Path ASSETS = Path.of("src", "test", "resources", "contract");

    @Test
    void contractTestAssetsMatchDocs() throws Exception {
        Assumptions.assumeTrue(Files.exists(DOCS.resolve("openapi.yaml")), "docs/ 없음(배포 브랜치): 원본 대조를 건너뛴다");
        List<String> problems = new ArrayList<>();
        if (!Files.readString(DOCS.resolve("openapi.yaml")).equals(Files.readString(ASSETS.resolve("openapi.yaml")))) {
            problems.add("openapi.yaml 다름");
        }
        TreeSet<String> docs = jsonFiles(DOCS);
        docs.remove("examples/preconditions.json");
        TreeSet<String> assets = jsonFiles(ASSETS);
        if (!docs.equals(assets)) {
            problems.add("예시 목록 다름: docs만 " + diff(docs, assets) + ", 자산만 " + diff(assets, docs));
        }
        for (String f : docs) {
            if (assets.contains(f) && !Files.readString(DOCS.resolve(f)).equals(Files.readString(ASSETS.resolve(f)))) {
                problems.add(f + " 다름");
            }
        }
        assertThat(problems).as("./gradlew syncContractTestAssets로 다시 복사하세요: " + problems).isEmpty();
    }

    private static TreeSet<String> jsonFiles(Path root) throws Exception {
        Path examples = root.resolve("examples");
        TreeSet<String> out = new TreeSet<>();
        if (!Files.exists(examples)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(examples)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> out.add(root.relativize(p).toString().replace('\\', '/')));
        }
        return out;
    }

    private static List<String> diff(TreeSet<String> a, TreeSet<String> b) {
        return a.stream().filter(x -> !b.contains(x)).toList();
    }
}
