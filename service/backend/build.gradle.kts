plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "kr.iroum"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // 사진 저장소 S3 구현(inspection.media.store=s3). 2026-09-28 배포 버전으로 고정. 동기 클라이언트만 쓰므로 Netty 비동기 클라이언트는 뺀다.
    implementation(platform("software.amazon.awssdk:bom:2.55.7"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // 테스트 전용: 실제 응답을 docs/openapi.yaml 스키마로 검증(JSON Schema 2020-12)
    testImplementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.19.2")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // 테스트 전용: S3 호환 컨테이너(LocalStack)로 S3 저장소 검증. 실제 AWS 호출 없음.
    testImplementation("org.testcontainers:testcontainers-localstack")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("user.timezone", "UTC")
}

// 계약 검사 테스트 전용 자산(src/test/resources/contract). 원본은 저장소의 docs/openapi.yaml·docs/examples이며
// 문서를 싣지 않는 배포 브랜치에서도 계약 검사가 돌도록 사본을 테스트 자원으로 둔다(운영 JAR에는 들어가지 않음).
// 계약을 고친 뒤 개발 브랜치에서 ./gradlew syncContractTestAssets로 사본을 갱신한다. 어긋나면 ContractAssetSyncTest가 실패한다.
tasks.register<Sync>("syncContractTestAssets") {
    group = "verification"
    description = "docs/openapi.yaml과 docs/examples의 JSON 예시를 계약 테스트 자산으로 복사한다(개발 브랜치 전용)."
    from("../docs") {
        include("openapi.yaml")
        include("examples/**/*.json")
        exclude("examples/preconditions.json")
    }
    into("src/test/resources/contract")
}
