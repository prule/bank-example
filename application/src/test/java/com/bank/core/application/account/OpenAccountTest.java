package com.bank.core.application.account;

import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.ClearingAccountMissingException;
import com.bank.core.domain.DuplicateAccountNumberException;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenAccountTest {

    private static final AccountNumber CLEARING = AccountNumber.of("CLEARING-000");
    private static final AccountNumber NEW = AccountNumber.of("NEW-001");
    private static final Money FIFTY = Money.of("50.00");

    private Accounts accounts;
    private TransferFunds transferFunds;
    private OpenAccount useCase;

    @BeforeEach
    void setUp() {
        accounts = mock(Accounts.class);
        transferFunds = mock(TransferFunds.class);
        useCase = new OpenAccount(accounts, transferFunds, CLEARING);
    }

    @Test
    void zeroOpen_createsActiveAccountAtZero_neverTouchesTransferFunds_neverReadsClearingAccount() {
        when(accounts.findByNumber(NEW))
                .thenReturn(Optional.empty()) // duplicate pre-check
                .thenReturn(Optional.of(Account.open(NEW, Money.ZERO))); // post-funding reload

        Account result = useCase.open(new OpenAccountCommand(NEW, Money.ZERO));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts, times(1)).save(saved.capture());
        Account savedAggregate = saved.getValue();
        assertEquals(NEW, savedAggregate.number());
        assertEquals(Money.ZERO, savedAggregate.balance());

        verify(transferFunds, never()).transfer(any());
        verify(accounts, never()).findByNumber(CLEARING);

        assertEquals(NEW, result.number());
        assertEquals(Money.ZERO, result.balance());
    }

    @Test
    void positiveOpen_clearingPresent_savesNewAccount_thenFundsViaTransferFunds() {
        Account clearing = Account.open(CLEARING, Money.of("1000.00"));
        Account funded = Account.open(NEW, Money.ZERO);
        funded.credit(FIFTY);

        when(accounts.findByNumber(NEW))
                .thenReturn(Optional.empty()) // duplicate pre-check
                .thenReturn(Optional.of(funded)); // post-funding reload
        when(accounts.findByNumber(CLEARING)).thenReturn(Optional.of(clearing));

        Account result = useCase.open(new OpenAccountCommand(NEW, FIFTY));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts, times(1)).save(saved.capture());
        assertEquals(NEW, saved.getValue().number());
        assertEquals(Money.ZERO, saved.getValue().balance(),
                "F08 saves the new account at zero before F06 funds it");

        ArgumentCaptor<TransferCommand> tx = ArgumentCaptor.forClass(TransferCommand.class);
        verify(transferFunds, times(1)).transfer(tx.capture());
        TransferCommand transfer = tx.getValue();
        assertEquals(CLEARING, transfer.source(), "source must be the clearing account");
        assertEquals(NEW, transfer.destination(), "destination must be the new account");
        assertEquals(FIFTY, transfer.amount());

        assertSame(funded, result, "result is the post-funding reload");
    }

    @Test
    void duplicateAccountNumber_throwsDuplicateException_neverSaves_neverInvokesTransfer() {
        Account existing = Account.open(NEW, Money.of("200.00"));
        when(accounts.findByNumber(NEW)).thenReturn(Optional.of(existing));

        DuplicateAccountNumberException ex = assertThrows(DuplicateAccountNumberException.class,
                () -> useCase.open(new OpenAccountCommand(NEW, FIFTY)));
        assertEquals(NEW, ex.number());

        verify(accounts, never()).save(any());
        verifyNoInteractions(transferFunds);
    }

    @Test
    void positiveOpen_clearingMissing_throwsClearingMissingException_neverSavesNewAccount() {
        when(accounts.findByNumber(NEW)).thenReturn(Optional.empty());
        when(accounts.findByNumber(CLEARING)).thenReturn(Optional.empty());

        ClearingAccountMissingException ex = assertThrows(ClearingAccountMissingException.class,
                () -> useCase.open(new OpenAccountCommand(NEW, FIFTY)));
        assertEquals(CLEARING, ex.clearingAccountNumber());

        verify(accounts, never()).save(any());
        verifyNoInteractions(transferFunds);
    }

    @Test
    void zeroOpen_clearingMissing_isAllowed() {
        when(accounts.findByNumber(NEW))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(Account.open(NEW, Money.ZERO)));

        Account result = useCase.open(new OpenAccountCommand(NEW, Money.ZERO));

        assertEquals(NEW, result.number());
        verify(accounts, never()).findByNumber(CLEARING);
        verifyNoInteractions(transferFunds);
    }

    @Test
    void duplicateCheckRunsBeforeClearingAccountCheck() {
        when(accounts.findByNumber(NEW)).thenReturn(Optional.of(Account.open(NEW, Money.ZERO)));

        assertThrows(DuplicateAccountNumberException.class,
                () -> useCase.open(new OpenAccountCommand(NEW, FIFTY)));

        verify(accounts, never()).findByNumber(CLEARING);
        verifyNoInteractions(transferFunds);
    }

    @Test
    void nullCommandRejected() {
        assertThrows(NullPointerException.class, () -> useCase.open(null));
        verifyNoInteractions(accounts, transferFunds);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
