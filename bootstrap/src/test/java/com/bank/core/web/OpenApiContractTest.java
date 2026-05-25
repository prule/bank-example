package com.bank.core.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
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

import java.net.URL;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Test
    void yamlResponseContainsCanonicalContent() {
        ResponseEntity<String> response = get(MediaType.parseMediaType("application/yaml"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/yaml");
        assertThat(response.getBody()).contains("openapi:").contains("ErrorEnvelope");
    }

    @Test
    void jsonResponseContainsCanonicalContent() {
        ResponseEntity<String> response = get(MediaType.APPLICATION_JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).contains("\"openapi\"").contains("ErrorEnvelope");
    }

    @Test
    void servedDocumentDeclaresLookupAccountOperation() {
        ResponseEntity<String> response = get(MediaType.APPLICATION_JSON);
        OpenAPI fromController = parse(response.getBody());

        Object pathItem = fromController.getPaths().get("/api/v1/accounts/{accountNumber}");
        assertThat(pathItem)
                .as("served document must declare GET /api/v1/accounts/{accountNumber}")
                .isNotNull();
        assertThat(fromController.getPaths().get("/api/v1/accounts/{accountNumber}").getGet().getOperationId())
                .isEqualTo("lookupAccount");
    }

    @Test
    void servedDocumentDeclaresCreateTransferOperation() {
        ResponseEntity<String> response = get(MediaType.APPLICATION_JSON);
        OpenAPI fromController = parse(response.getBody());

        Object pathItem = fromController.getPaths().get("/api/v1/transfers");
        assertThat(pathItem)
                .as("served document must declare POST /api/v1/transfers")
                .isNotNull();
        assertThat(fromController.getPaths().get("/api/v1/transfers").getPost().getOperationId())
                .isEqualTo("createTransfer");
    }

    @Test
    void servedDocumentDeclaresTransferRequestSchemaWithRequiredFields() {
        ResponseEntity<String> response = get(MediaType.APPLICATION_JSON);
        OpenAPI fromController = parse(response.getBody());

        Schema<?> transferRequest = fromController.getComponents().getSchemas().get("TransferRequest");
        assertThat(transferRequest)
                .as("served document must declare TransferRequest schema")
                .isNotNull();
        assertThat(transferRequest.getRequired())
                .containsExactlyInAnyOrder("sourceAccountNumber", "destinationAccountNumber", "amount");
    }

    @Test
    void servedDocumentDeclaresGetIndexOperation() {
        ResponseEntity<String> response = get(MediaType.APPLICATION_JSON);
        OpenAPI fromController = parse(response.getBody());

        Object pathItem = fromController.getPaths().get("/api/v1");
        assertThat(pathItem)
                .as("served document must declare GET /api/v1")
                .isNotNull();
        assertThat(fromController.getPaths().get("/api/v1").getGet().getOperationId())
                .isEqualTo("getIndex");
    }

    @Test
    void servedDocumentDeclaresLinkAndIndexResponseSchemas() {
        ResponseEntity<String> response = get(MediaType.APPLICATION_JSON);
        OpenAPI fromController = parse(response.getBody());
        Map<String, ?> schemas = fromController.getComponents().getSchemas();

        assertThat(schemas).containsKeys("Link", "IndexResponse");
        Schema<?> link = (Schema<?>) schemas.get("Link");
        assertThat(link.getRequired()).containsExactly("href");
    }

    @Test
    void accountResponseSchemaRequiresLinks() {
        ResponseEntity<String> response = get(MediaType.APPLICATION_JSON);
        OpenAPI fromController = parse(response.getBody());

        Schema<?> accountResponse = fromController.getComponents().getSchemas().get("AccountResponse");
        assertThat(accountResponse.getRequired())
                .as("AccountResponse must require _links after HATEOAS change")
                .contains("_links");
    }

    @Test
    void servedDocumentDeclaresAccountResponseSchemaWithAllThreeStatuses() {
        ResponseEntity<String> response = get(MediaType.APPLICATION_JSON);
        OpenAPI fromController = parse(response.getBody());

        Schema<?> accountResponse = fromController.getComponents().getSchemas().get("AccountResponse");
        assertThat(accountResponse)
                .as("served document must declare AccountResponse schema")
                .isNotNull();

        Schema<?> status = (Schema<?>) accountResponse.getProperties().get("status");
        assertThat(status).isNotNull();
        java.util.List<String> values = status.getEnum().stream()
                .map(Object::toString)
                .toList();
        assertThat(values)
                .as("status enum must include all three returnable statuses")
                .containsExactlyInAnyOrder("ACTIVE", "SUSPENDED", "CLOSED");
    }

    private static OpenAPI parse(String body) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        return new OpenAPIV3Parser().readContents(body, null, options).getOpenAPI();
    }

    @Test
    void servedDocumentMatchesCanonicalClasspathContract() {
        URL classpathContract = getClass().getClassLoader().getResource("openapi/openapi.yaml");
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        OpenAPI fromClasspath = new OpenAPIV3Parser()
                .readLocation(classpathContract.toString(), null, options)
                .getOpenAPI();

        ResponseEntity<String> response = get(MediaType.APPLICATION_JSON);
        OpenAPI fromController = new OpenAPIV3Parser()
                .readContents(response.getBody(), null, options)
                .getOpenAPI();

        Map<String, ?> classpathPaths = fromClasspath.getPaths();
        Map<String, ?> controllerPaths = fromController.getPaths();
        assertThat(controllerPaths.keySet()).isEqualTo(classpathPaths.keySet());

        Map<String, ?> classpathSchemas = fromClasspath.getComponents().getSchemas();
        Map<String, ?> controllerSchemas = fromController.getComponents().getSchemas();
        assertThat(controllerSchemas.keySet()).isEqualTo(classpathSchemas.keySet());
    }

    private ResponseEntity<String> get(MediaType accept) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(accept));
        return rest.exchange(
                "http://localhost:" + port + "/v3/api-docs",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }
}
