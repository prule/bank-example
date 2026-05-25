package com.bank.core.web.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IndexControllerTest {

    private static final MediaType HAL_JSON = MediaType.parseMediaType("application/hal+json");
    private static final Set<String> EXPECTED_RELS = Set.of("self", "accounts", "transfers", "openapi");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private String url() {
        return "http://localhost:" + port + "/api/v1";
    }

    @Test
    void indexReturns200WithExactlyTheFourRootLinks() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(url(), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(fieldNames(body)).containsExactly("_links");

        JsonNode links = body.get("_links");
        assertThat(fieldNames(links)).isEqualTo(EXPECTED_RELS);

        assertThat(links.get("self").get("href").asText()).isEqualTo("/api/v1");
        assertThat(links.get("accounts").get("href").asText()).isEqualTo("/api/v1/accounts/{accountNumber}");
        assertThat(links.get("accounts").get("templated").asBoolean()).isTrue();
        assertThat(links.get("transfers").get("href").asText()).isEqualTo("/api/v1/transfers");
        assertThat(links.get("openapi").get("href").asText()).isEqualTo("/v3/api-docs");
    }

    @Test
    void selfLinkIsNotTemplated() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(url(), String.class);

        JsonNode self = mapper.readTree(response.getBody()).get("_links").get("self");
        assertThat(self.get("templated").asBoolean()).isFalse();
    }

    @Test
    void halAcceptHeaderReturnsHalJsonContentType() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(HAL_JSON));
        ResponseEntity<String> response = rest.exchange(
                url(), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/hal+json");
    }

    @Test
    void defaultAcceptReturnsApplicationJson() {
        ResponseEntity<String> response = rest.getForEntity(url(), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().includes(MediaType.APPLICATION_JSON)).isTrue();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Iterator<String> it = node.fieldNames();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        while (it.hasNext()) names.add(it.next());
        return names;
    }
}
