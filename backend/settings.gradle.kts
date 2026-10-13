plugins {
    // Java 21 툴체인이 없는 개발 PC에서 Temurin을 자동으로 내려받는다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "inspection-backend"
