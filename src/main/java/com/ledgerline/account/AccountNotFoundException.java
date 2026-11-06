package com.ledgerline.account;

import com.ledgerline.common.DomainException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends DomainException {

    public AccountNotFoundException(UUID id) {
        super(
            HttpStatus.NOT_FOUND,
            "ACCOUNT_NOT_FOUND",
            "Account " + id + " does not exist"
        );
    }
}
