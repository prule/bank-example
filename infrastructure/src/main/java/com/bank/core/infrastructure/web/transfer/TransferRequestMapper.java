package com.bank.core.infrastructure.web.transfer;

import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;
import com.bank.core.dto.TransferRequest;
import org.springframework.stereotype.Component;

@Component
class TransferRequestMapper {

    TransferCommand toCommand(TransferRequest request) {
        return new TransferCommand(
                AccountNumber.of(request.getSourceAccountNumber()),
                AccountNumber.of(request.getDestinationAccountNumber()),
                Money.of(request.getAmount())
        );
    }
}
