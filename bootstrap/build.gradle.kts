plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":infrastructure"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
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
