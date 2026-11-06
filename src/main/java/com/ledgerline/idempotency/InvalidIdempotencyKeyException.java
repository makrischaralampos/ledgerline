package com.ledgerline.idempotency;

import com.ledgerline.common.DomainException;
import org.springframework.http.HttpStatus;

public class InvalidIdempotencyKeyException extends DomainException {

    public InvalidIdempotencyKeyException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
