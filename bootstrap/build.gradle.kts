plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":infrastructure"))
    // F06 wires the TransferFunds use case + Clock as @Bean factory methods
    // in BankCoreApplication, so the main scope needs the application and
    // domain types directly (infrastructure exposes them as implementation,
    // not api, so transitive visibility doesn't carry through to bootstrap).
    implementation(project(":application"))
    implementation(project(":domain"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Micrometer Prometheus registry — Spring Boot auto-configures
    // /actuator/prometheus once this is on the classpath. Version managed
    // by the spring-boot-dependencies BOM applied in the root build.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-core")
    implementation("com.h2database:h2")

    // Swagger UI under the `dev` profile only — gated by springdoc.swagger-ui.enabled.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.0")

    testImplementation(project(":domain"))
    testImplementation(project(":application"))
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.22")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
