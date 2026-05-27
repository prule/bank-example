plugins {
    java
    id("org.springframework.boot") version "3.5.14" // Or your preferred stable Spring Boot 3.x version
    id("io.spring.dependency-management") version "1.1.6"
    id("org.openapi.generator") version "7.8.0"
}

group = "com.bank"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17)) // Java 17+ required for modern Spring Boot 3
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation") // Enforces contract constraints

    // Database & Driver
//    runtimeOnly("org.postgresql:postgresql") // Production ready storage

    // OpenAPI Generation Dependencies
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    implementation("org.openapitools:jackson-databind-nullable:0.2.6")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // junit5
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")

    // Database Drivers
    implementation("com.h2database:h2")
}

// Configure the OpenAPI Generator Task
openApiGenerate {
    generatorName.set("spring")
    inputSpec.set("$projectDir/src/main/resources/static/openapi/openapi.yaml")
    outputDir.set("$buildDir/generated/openapi")
    apiPackage.set("com.bank.core.api")
    modelPackage.set("com.bank.core.dto")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",         // Generates interfaces without default controller implementations
            "useSpringBoot3" to "true",        // Ensures jakarta.* namespaces are used instead of javax.*
            "openApiNullable" to "false",
            "skipDefaultInterface" to "true",   // Prevents generation of unused boilerplates
            "useTags" to "true" // Force separate interface files
        )
    )
}

// Force Gradle to generate API interfaces before compiling Java source files
tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

// Include the generated files into the main Java source directories
sourceSets {
    main {
        java {
            srcDir("$buildDir/generated/openapi/src/main/java")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
