package com.bank.core.web.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Throwaway controller mounted only inside F03 tests to exercise each handler
 * path in {@link com.bank.core.infrastructure.web.error.GlobalExceptionHandler}.
 * Registered via {@code @TestConfiguration}; never on the production classpath.
 */
@RestController
@RequestMapping("/internal/test-errors")
public class ErrorTestController {

    @GetMapping("/validation")
    public ResponseEntity<Void> validation(@RequestParam(name = "amount") @NotNull Long amount) {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/parse")
    public ResponseEntity<Void> parse(@Valid @RequestBody Payload payload) {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/boom")
    public ResponseEntity<Void> boom() {
        throw new RuntimeException("internal probe failure");
    }

    public static class Payload {
        @NotBlank
        private String field;

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
    }
}
