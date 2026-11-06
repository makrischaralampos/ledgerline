package com.ledgerline.account;

import com.ledgerline.common.DomainException;
import org.springframework.http.HttpStatus;

public class UnsupportedCurrencyException extends DomainException {

    public UnsupportedCurrencyException(String currency) {
        super(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "UNSUPPORTED_CURRENCY",
            "Currency " + currency + " is not supported"
        );
    }
}
