plugins {
    id("org.openapi.generator") version "7.10.0"
}

dependencies {
    implementation(project(":application"))
    implementation(project(":domain"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-hateoas")

    // Micrometer core — provides MeterRegistry, Timer, Counter, Tags used by
    // infrastructure-boundary instrumentation (TransferMetrics, JournalPendingGauge,
    // schedulers, locker adapters). The Prometheus registry sits on the bootstrap
    // classpath; this module only needs the core interfaces. Version managed by
    // the Spring Boot BOM applied in the root build.
    implementation("io.micrometer:micrometer-core")

    // Used by OpenApiController to load and inline-resolve the canonical contract at startup.
    implementation("io.swagger.parser.v3:swagger-parser:2.1.22")

    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val openApiOutputDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set("$rootDir/bootstrap/src/main/resources/openapi/openapi.yaml")
    outputDir.set(openApiOutputDir.get().asFile.absolutePath)
    apiPackage.set("com.bank.core.api")
    modelPackage.set("com.bank.core.dto")
    invokerPackage.set("com.bank.core.api.invoker")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useSpringBoot3" to "true",
            "useJakartaEe" to "true",
            "openApiNullable" to "false",
            "skipDefaultInterface" to "true",
            "performBeanValidation" to "true",
            "useTags" to "true",
            "documentationProvider" to "none",
            "annotationLibrary" to "none"
        )
    )
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

sourceSets["main"].java.srcDir(openApiOutputDir.map { it.dir("src/main/java") })

tasks.named("compileJava") {
    dependsOn("openApiGenerate")
}
