package com.bank.core.infrastructure.web;

import com.bank.core.dto.IndexResponse;
import com.bank.core.dto.IndexResponseLinks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Plain JUnit unit test for {@link IndexModelAssembler}. No Spring context,
 * no MockMvc, no random port. Locks down the four-relation {@code _links}
 * payload (self, accounts, transfers, openapi) including the
 * {@code accounts} templated-link flag.
 */
class IndexModelAssemblerTest {

    private final IndexModelAssembler assembler = new IndexModelAssembler();

    @Test
    void toModelReturnsAllFourLinksWithExpectedHrefs() {
        IndexResponse response = assembler.toModel();
        IndexResponseLinks links = response.getLinks();
        assertNotNull(links);

        assertEquals("/api/v1", links.getSelf().getHref());
        assertEquals("/api/v1/accounts/{accountNumber}", links.getAccounts().getHref());
        assertEquals("/api/v1/transfers", links.getTransfers().getHref());
        assertEquals("/v3/api-docs", links.getOpenapi().getHref());
    }

    @Test
    void accountsLinkIsFlaggedTemplated() {
        IndexResponseLinks links = assembler.toModel().getLinks();

        assertEquals(Boolean.TRUE, links.getAccounts().getTemplated());
    }

    @Test
    void nonTemplatedRelationsAreNotFlaggedTemplated() {
        IndexResponseLinks links = assembler.toModel().getLinks();

        // self, transfers, openapi: templated must be unset (null) or false.
        assertNotEquals(Boolean.TRUE, links.getSelf().getTemplated());
        assertNotEquals(Boolean.TRUE, links.getTransfers().getTemplated());
        assertNotEquals(Boolean.TRUE, links.getOpenapi().getTemplated());
    }
}
