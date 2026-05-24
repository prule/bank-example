plugins {
    java
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.springframework.boot") version "3.4.5" apply false
}

allprojects {
    group = "com.bank.core"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.5")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Java 25 is newer than the bundled ByteBuddy supports; opt in to
        // experimental class-file parsing so Mockito's inline mock maker
        // can instrument JDK 25 classes (Mockito tracks this in #3492).
        systemProperty("net.bytebuddy.experimental", "true")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
