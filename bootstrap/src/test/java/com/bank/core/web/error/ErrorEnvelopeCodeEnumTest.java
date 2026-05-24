package com.bank.core.web.error;

import com.bank.core.dto.ErrorEnvelope.CodeEnum;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErrorEnvelopeCodeEnumTest {

    private static final Set<String> EXPECTED = Set.of(
            "INSUFFICIENT_FUNDS",
            "ACCOUNT_INACTIVE",
            "RESOURCE_NOT_FOUND",
            "BAD_REQUEST_PAYLOAD",
            "INTERNAL_SERVER_ERROR");

    @Test
    void enumExposesExactlyTheCanonicalTaxonomy() {
        Set<String> actual = Stream.of(CodeEnum.values()).map(CodeEnum::getValue).collect(Collectors.toSet());
        assertThat(actual).isEqualTo(EXPECTED);
    }

    @Test
    void fromValueRejectsUnknownCodes() {
        assertThatThrownBy(() -> CodeEnum.fromValue("BANANAS"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
