package com.bank.core.infrastructure.web.account;

import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.Money;
import com.bank.core.dto.AccountResponse;
import com.bank.core.dto.AccountResponseLinks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Plain JUnit unit test for {@link AccountModelAssembler}. No Spring context,
 * no MockMvc, no random port. Exercises the assembler directly to confirm it
 * is the single owner of the {@link Account} → {@link AccountResponse}
 * transformation, including link construction.
 */
class AccountModelAssemblerTest {

    private final AccountModelAssembler assembler =
            new AccountModelAssembler(new AccountResponseMapper());

    @Test
    void toModelPopulatesAllFieldsAndBothLinksForActiveAccount() {
        Account account = Account.open(AccountNumber.of("ACC-200"), Money.of("100.00"));

        AccountResponse response = assembler.toModel(account);

        assertEquals("ACC-200", response.getAccountNumber());
        assertEquals("100.00", response.getBalance());
        assertEquals(AccountResponse.StatusEnum.ACTIVE, response.getStatus());

        AccountResponseLinks links = response.getLinks();
        assertNotNull(links, "_links payload must be set");
        assertEquals("/api/v1/accounts/ACC-200", links.getSelf().getHref());
        assertEquals(Boolean.FALSE, links.getSelf().getTemplated());
        assertEquals("/api/v1/transfers", links.getTransfers().getHref());
        assertEquals(Boolean.FALSE, links.getTransfers().getTemplated());
    }

    @Test
    void toModelHandlesSuspendedAccount() {
        Account opened = Account.open(AccountNumber.of("ACC-SUS"), Money.of("12.34"));
        Account suspended = Account.rehydrate(
                opened.id(), opened.number(), opened.balance(), AccountStatus.SUSPENDED);

        AccountResponse response = assembler.toModel(suspended);

        assertEquals("ACC-SUS", response.getAccountNumber());
        assertEquals("12.34", response.getBalance());
        assertEquals(AccountResponse.StatusEnum.SUSPENDED, response.getStatus());
        assertEquals("/api/v1/accounts/ACC-SUS", response.getLinks().getSelf().getHref());
    }

    @Test
    void toCollectionModelAppliesToModelToEveryInput() {
        Account a = Account.open(AccountNumber.of("ACC-1"), Money.of("1.00"));
        Account b = Account.open(AccountNumber.of("ACC-2"), Money.of("2.00"));

        List<AccountResponse> responses = assembler.toCollectionModel(List.of(a, b));

        assertEquals(2, responses.size());
        assertEquals("ACC-1", responses.get(0).getAccountNumber());
        assertEquals("/api/v1/accounts/ACC-1", responses.get(0).getLinks().getSelf().getHref());
        assertEquals("ACC-2", responses.get(1).getAccountNumber());
        assertEquals("/api/v1/accounts/ACC-2", responses.get(1).getLinks().getSelf().getHref());
    }

    @Test
    void toCollectionModelOnEmptyInputReturnsEmptyList() {
        List<AccountResponse> responses = assembler.toCollectionModel(List.of());

        assertEquals(0, responses.size());
    }
}
