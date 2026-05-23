package com.bank.core.domain;

public class AccountInactiveException extends  RuntimeException {
    public AccountInactiveException(String message) {
        super(message);
    }

}
