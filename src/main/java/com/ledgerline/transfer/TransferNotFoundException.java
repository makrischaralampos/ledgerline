package com.ledgerline.transfer;

import com.ledgerline.common.DomainException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class TransferNotFoundException extends DomainException {

    public TransferNotFoundException(UUID id) {
        super(
            HttpStatus.NOT_FOUND,
            "TRANSFER_NOT_FOUND",
            "Transfer " + id + " does not exist"
        );
    }
}
