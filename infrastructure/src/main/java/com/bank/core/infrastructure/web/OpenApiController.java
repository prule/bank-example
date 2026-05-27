package com.bank.core.infrastructure.web;

import com.bank.core.infrastructure.openapi.OpenApiDocService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OpenApiController {

    private final OpenApiDocService openApiDocService;

    public OpenApiController(OpenApiDocService openApiDocService) {
        this.openApiDocService = openApiDocService;
    }

    @GetMapping("/v3/api-docs")
    public ResponseEntity<String> getApiDocs(@RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader) {
        if (acceptHeader != null && (acceptHeader.contains("application/yaml") || acceptHeader.contains("text/yaml"))) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/yaml")
                    .body(openApiDocService.getYamlContent());
        }
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(openApiDocService.getJsonContent());
    }
}
