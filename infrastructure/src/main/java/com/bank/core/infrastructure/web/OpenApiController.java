package com.bank.core.infrastructure.web;

import com.bank.core.api.OpenapiApi;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URL;
import java.util.List;

@RestController
public class OpenApiController implements OpenapiApi {

    static final String CONTRACT_RESOURCE = "openapi/openapi.yaml";
    static final MediaType APPLICATION_YAML = MediaType.parseMediaType("application/yaml");

    private final String yamlBody;
    private final String jsonBody;

    public OpenApiController() {
        OpenAPI openAPI = loadContract();
        this.yamlBody = Yaml.pretty(openAPI);
        this.jsonBody = Json.pretty(openAPI);
    }

    @Override
    public ResponseEntity<String> getOpenApiDocument() {
        boolean wantsYaml = preferredAcceptIsYaml();
        MediaType contentType = wantsYaml ? APPLICATION_YAML : MediaType.APPLICATION_JSON;
        String body = wantsYaml ? yamlBody : jsonBody;
        return ResponseEntity.ok().contentType(contentType).body(body);
    }

    private static OpenAPI loadContract() {
        URL location = OpenApiController.class.getClassLoader().getResource(CONTRACT_RESOURCE);
        if (location == null) {
            throw new IllegalStateException(
                    "Canonical OpenAPI contract not found on classpath at " + CONTRACT_RESOURCE);
        }
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(location.toString(), null, options);
        OpenAPI openAPI = result.getOpenAPI();
        if (openAPI == null) {
            throw new IllegalStateException(
                    "Failed to parse canonical OpenAPI contract: " + result.getMessages());
        }
        return openAPI;
    }

    private static boolean preferredAcceptIsYaml() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return false;
        }
        String accept = attrs.getRequest().getHeader(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank()) {
            return false;
        }
        for (MediaType mediaType : MediaType.parseMediaTypes(accept)) {
            if (mediaType.isCompatibleWith(APPLICATION_YAML)
                    || mediaType.getSubtype().equalsIgnoreCase("yaml")
                    || mediaType.getSubtype().endsWith("+yaml")) {
                return true;
            }
            if (mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                return false;
            }
        }
        return false;
    }
}
