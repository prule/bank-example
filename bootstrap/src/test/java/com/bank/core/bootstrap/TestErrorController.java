package com.bank.core.bootstrap;

import com.bank.core.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test-errors")
public class TestErrorController {

    public static class ValidationPayload {
        @NotBlank(message = "is required")
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @GetMapping("/insufficient-funds")
    public void throwInsufficientFunds() {
        throw new InsufficientFundsException(
                AccountId.generate(),
                Money.of("50.00"),
                Money.of("10.00")
        );
    }

    @GetMapping("/account-inactive")
    public void throwAccountInactive() {
        throw new AccountInactiveException(
                AccountId.generate(),
                AccountStatus.SUSPENDED
        );
    }

    @GetMapping("/invalid-amount")
    public void throwInvalidAmount() {
        throw new InvalidAmountException(Money.of("0.00"));
    }

    @GetMapping("/illegal-status")
    public void throwIllegalStatus() {
        throw new IllegalStatusTransitionException(
                AccountId.generate(),
                AccountStatus.CLOSED,
                AccountStatus.ACTIVE
        );
    }

    @GetMapping("/generic-runtime")
    public void throwGenericRuntime() {
        throw new RuntimeException("Dangerous database exception: SELECT * FROM users; com.bank.core.domain.Account; java.lang.NullPointerException");
    }

    @PostMapping("/validation")
    public void triggerValidation(@Valid @RequestBody ValidationPayload payload) {
        // no-op
    }
}
