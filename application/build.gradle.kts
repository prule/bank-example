plugins {
    java
}

dependencies {
    implementation(project(":domain"))
    implementation("org.slf4j:slf4j-api:2.0.12")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
