package com.bank.core.web.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotFoundHandlerTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Test
    void unknownGetPathReturnsCanonical404Envelope() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(
                "http://localhost:" + port + "/no/such/path", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body.get("message").asText()).contains("/no/such/path");
        OffsetDateTime.parse(body.get("timestamp").asText());
    }

    @Test
    void unknownOptionsPathReturnsCanonical404Envelope() throws Exception {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/no/such/path",
                HttpMethod.OPTIONS, new HttpEntity<>(null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }
}
