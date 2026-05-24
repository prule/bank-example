package com.bank.core.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class SwaggerUiDevProfileTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Test
    void swaggerUiHtmlIsReachableUnderDevProfile() {
        ResponseEntity<String> entry = rest.getForEntity(
                "http://localhost:" + port + "/swagger-ui.html", String.class);
        assertThat(entry.getStatusCode().is3xxRedirection() || entry.getStatusCode().is2xxSuccessful())
                .as("/swagger-ui.html should redirect to or render the UI under dev")
                .isTrue();

        ResponseEntity<String> index = rest.getForEntity(
                "http://localhost:" + port + "/swagger-ui/index.html", String.class);
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(index.getBody()).contains("Swagger UI");
    }

    @Test
    void swaggerInitializerPointsAtHandWrittenContract() {
        ResponseEntity<String> init = rest.getForEntity(
                "http://localhost:" + port + "/swagger-ui/swagger-initializer.js", String.class);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> swaggerConfig = rest.getForEntity(
                "http://localhost:" + port + "/internal/springdoc-api-docs/swagger-config", String.class);
        assertThat(swaggerConfig.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(swaggerConfig.getBody()).contains("\"url\":\"/v3/api-docs\"");
    }
}
