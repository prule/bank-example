package com.bank.core.infrastructure.openapi;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OpenApiDocService {
    private static final Logger log = LoggerFactory.getLogger(OpenApiDocService.class);

    private String yamlContent;
    private String jsonContent;

    @PostConstruct
    public void init() {
        try {
            log.info("Assembling modular OpenAPI document from classpath:openapi/openapi.yaml...");
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            options.setResolveFully(true);

            OpenAPI openAPI = new OpenAPIV3Parser().read("openapi/openapi.yaml", null, options);
            if (openAPI == null) {
                throw new IllegalStateException("Failed to parse openapi/openapi.yaml: parser returned null");
            }

            this.yamlContent = Yaml.pretty(openAPI);
            this.jsonContent = Json.pretty(openAPI);
            log.info("OpenAPI document successfully assembled and cached.");
        } catch (Exception e) {
            log.error("Failed to assemble modular OpenAPI document", e);
            throw new RuntimeException("Failed to initialize OpenAPI document service", e);
        }
    }

    public String getYamlContent() {
        return yamlContent;
    }

    public String getJsonContent() {
        return jsonContent;
    }
}
