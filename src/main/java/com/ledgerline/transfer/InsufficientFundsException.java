package com.ledgerline.transfer;

import com.ledgerline.common.DomainException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends DomainException {

    public InsufficientFundsException(
        UUID accountId,
        long balance,
        long requested
    ) {
        super(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INSUFFICIENT_FUNDS",
            "Account " +
                accountId +
                " has balance " +
                balance +
                " but " +
                requested +
                " was requested"
        );
    }
}
