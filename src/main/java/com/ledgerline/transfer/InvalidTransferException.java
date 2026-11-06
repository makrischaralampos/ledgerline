package com.ledgerline.transfer;

import com.ledgerline.common.DomainException;
import org.springframework.http.HttpStatus;

/** A well-formed request that violates a business rule (same account, currency mismatch, ...). */
public class InvalidTransferException extends DomainException {

    public InvalidTransferException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
