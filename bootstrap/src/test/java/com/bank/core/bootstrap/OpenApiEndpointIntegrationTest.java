package com.bank.core.bootstrap;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class OpenApiEndpointIntegrationTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    public class DefaultProfileTest {
        @Autowired
        private TestRestTemplate restTemplate;

        @Test
        public void testApiDocsExposed() {
            ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("openapi");
            assertThat(response.getBody()).contains("ErrorEnvelope");
            assertThat(response.getBody()).contains("AccountResponse");
            assertThat(response.getBody()).contains("TransferRequest");
        }

        @Test
        public void testSwaggerUiNotExposed() {
            ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui.html", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @ActiveProfiles({"test", "dev"})
    @AutoConfigureMockMvc
    public class DevProfileTest {
        @Autowired
        private MockMvc mockMvc;

        @Test
        public void testSwaggerUiRedirects() throws Exception {
            mockMvc.perform(get("/swagger-ui.html"))
                    .andExpect(status().isFound()) // 302 redirect
                    .andExpect(header().string("Location", "/webjars/swagger-ui/index.html?url=/v3/api-docs"));
        }
    }
}
