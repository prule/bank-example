package com.bank.core.application.seed;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.account.OpenAccountCommand;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.DuplicateAccountNumberException;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SeedDataTest {

    private static final AccountNumber CLEARING = AccountNumber.of("CLEARING-000");
    private static final AccountNumber CUST_A = AccountNumber.of("CUST-A");
    private static final AccountNumber CUST_B = AccountNumber.of("CUST-B");
    private static final AccountNumber CUST_C = AccountNumber.of("CUST-C");
    private static final Money CLEARING_BALANCE = Money.of("1000.00");

    private Accounts accounts;
    private OpensAccount opensAccount;

    @BeforeEach
    void setUp() {
        accounts = mock(Accounts.class);
        opensAccount = mock(OpensAccount.class);
    }

    private SeedData useCase(List<CustomerSeed> customers) {
        SeedPlan plan = new SeedPlan(
                new ClearingAccountSeed(CLEARING, CLEARING_BALANCE),
                customers);
        return new SeedData(accounts, opensAccount, plan);
    }

    @Test
    void freshDb_savesClearingFirstThenOpensCustomersInOrder() {
        when(accounts.findByNumber(CLEARING)).thenReturn(Optional.empty());
        when(opensAccount.open(any())).thenAnswer(inv -> {
            OpenAccountCommand cmd = inv.getArgument(0);
            return Account.open(cmd.number(), cmd.openingBalance());
        });

        SeedData useCase = useCase(List.of(
                new CustomerSeed(CUST_A, Money.of("100.00")),
                new CustomerSeed(CUST_B, Money.of("50.00")),
                new CustomerSeed(CUST_C, Money.ZERO)));

        SeedReport report = useCase.seed();

        ArgumentCaptor<Account> savedClearing = ArgumentCaptor.forClass(Account.class);
        verify(accounts, times(1)).save(savedClearing.capture());
        Account clearing = savedClearing.getValue();
        assertEquals(CLEARING, clearing.number());
        assertEquals(CLEARING_BALANCE, clearing.balance(),
                "clearing account is saved at its configured opening balance directly");

        ArgumentCaptor<OpenAccountCommand> opens = ArgumentCaptor.forClass(OpenAccountCommand.class);
        verify(opensAccount, times(3)).open(opens.capture());
        List<OpenAccountCommand> calls = opens.getAllValues();
        assertEquals(CUST_A, calls.get(0).number());
        assertEquals(Money.of("100.00"), calls.get(0).openingBalance());
        assertEquals(CUST_B, calls.get(1).number());
        assertEquals(Money.of("50.00"), calls.get(1).openingBalance());
        assertEquals(CUST_C, calls.get(2).number());
        assertEquals(Money.ZERO, calls.get(2).openingBalance());

        SeedReport.Seeded seeded = assertInstanceOf(SeedReport.Seeded.class, report);
        assertEquals(CLEARING, seeded.clearingAccountNumber());
        assertEquals(List.of(CUST_A, CUST_B, CUST_C), seeded.customerAccountNumbers());
    }

    @Test
    void reRun_clearingPresent_returnsSkipped_withoutAnyWritesOrCustomerOpens() {
        Account existingClearing = Account.open(CLEARING, CLEARING_BALANCE);
        when(accounts.findByNumber(CLEARING)).thenReturn(Optional.of(existingClearing));

        SeedData useCase = useCase(List.of(new CustomerSeed(CUST_A, Money.of("10.00"))));

        SeedReport report = useCase.seed();

        verify(accounts, never()).save(any());
        verifyNoInteractions(opensAccount);

        SeedReport.Skipped skipped = assertInstanceOf(SeedReport.Skipped.class, report);
        assertEquals("clearing account already present", skipped.reason());
    }

    @Test
    void failureMidwayOnCustomer_propagatesException_priorCustomersAlreadyOpened() {
        when(accounts.findByNumber(CLEARING)).thenReturn(Optional.empty());
        RuntimeException simulated = new RuntimeException("simulated F06 failure");
        when(opensAccount.open(any())).thenAnswer(inv -> {
            OpenAccountCommand cmd = inv.getArgument(0);
            if (cmd.number().equals(CUST_B)) {
                throw simulated;
            }
            return Account.open(cmd.number(), cmd.openingBalance());
        });

        SeedData useCase = useCase(List.of(
                new CustomerSeed(CUST_A, Money.of("10.00")),
                new CustomerSeed(CUST_B, Money.of("20.00")),
                new CustomerSeed(CUST_C, Money.of("30.00"))));

        RuntimeException thrown = assertThrows(RuntimeException.class, useCase::seed);
        assertSame(simulated, thrown);

        ArgumentCaptor<OpenAccountCommand> opens = ArgumentCaptor.forClass(OpenAccountCommand.class);
        verify(opensAccount, times(2)).open(opens.capture());
        List<OpenAccountCommand> calls = opens.getAllValues();
        assertEquals(CUST_A, calls.get(0).number(),
                "first customer is opened before the failing one");
        assertEquals(CUST_B, calls.get(1).number(),
                "failing customer is invoked once before propagation");
    }

    @Test
    void customerNumberCollidesWithClearingNumber_propagatesDuplicateAccountException() {
        when(accounts.findByNumber(CLEARING)).thenReturn(Optional.empty());
        DuplicateAccountNumberException dup = new DuplicateAccountNumberException(CLEARING);
        when(opensAccount.open(any())).thenThrow(dup);

        SeedData useCase = useCase(List.of(new CustomerSeed(CLEARING, Money.of("1.00"))));

        DuplicateAccountNumberException thrown = assertThrows(DuplicateAccountNumberException.class,
                useCase::seed);
        assertSame(dup, thrown);
        assertEquals(CLEARING, thrown.number());

        verify(accounts, times(1)).save(any());
    }

    @Test
    void constructor_rejectsNullArgs() {
        SeedPlan plan = new SeedPlan(
                new ClearingAccountSeed(CLEARING, CLEARING_BALANCE),
                List.of());
        assertEquals("accounts cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new SeedData(null, opensAccount, plan)).getMessage());
        assertEquals("opensAccount cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new SeedData(accounts, null, plan)).getMessage());
        assertEquals("plan cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new SeedData(accounts, opensAccount, null)).getMessage());
    }
}
