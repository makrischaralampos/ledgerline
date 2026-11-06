package com.ledgerline.idempotency;

import com.ledgerline.common.DomainException;
import org.springframework.http.HttpStatus;

public class IdempotencyKeyReusedException extends DomainException {

    public IdempotencyKeyReusedException(String key) {
        super(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency key '" +
                key +
                "' was already used with a different request"
        );
    }
}
