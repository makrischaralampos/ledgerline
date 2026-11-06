package com.ledgerline.transfer;

import com.ledgerline.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TransferController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final long MAX_AMOUNT = 1_000_000_000_000L; // minor units; guards against overflow

    private final TransferService service;
    private final IdempotencyService idempotency;

    public TransferController(
        TransferService service,
        IdempotencyService idempotency
    ) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> create(
        @RequestHeader(
            name = IDEMPOTENCY_KEY_HEADER,
            required = false
        ) String idempotencyKey,
        @Valid @RequestBody CreateTransferRequest request
    ) {
        String fingerprint = IdempotencyService.fingerprint(
            "POST /transfers",
            request.sourceAccountId(),
            request.destinationAccountId(),
            request.amount(),
            request.currency(),
            request.reference()
        );

        return respond(
            idempotency.execute(
                idempotencyKey,
                fingerprint,
                TransferResponse.class,
                HttpStatus.CREATED,
                () ->
                    TransferResponse.from(
                        service.transfer(
                            request.sourceAccountId(),
                            request.destinationAccountId(),
                            request.amount(),
                            request.currency(),
                            request.reference()
                        )
                    )
            )
        );
    }

    @PostMapping("/accounts/{accountId}/deposits")
    public ResponseEntity<TransferResponse> deposit(
        @RequestHeader(
            name = IDEMPOTENCY_KEY_HEADER,
            required = false
        ) String idempotencyKey,
        @PathVariable UUID accountId,
        @Valid @RequestBody DepositRequest request
    ) {
        String fingerprint = IdempotencyService.fingerprint(
            "POST /accounts/deposits",
            accountId,
            request.amount(),
            request.currency(),
            request.reference()
        );

        return respond(
            idempotency.execute(
                idempotencyKey,
                fingerprint,
                TransferResponse.class,
                HttpStatus.CREATED,
                () ->
                    TransferResponse.from(
                        service.deposit(
                            accountId,
                            request.amount(),
                            request.currency(),
                            request.reference()
                        )
                    )
            )
        );
    }

    @GetMapping("/transfers/{id}")
    public TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.from(service.get(id));
    }

    private static ResponseEntity<TransferResponse> respond(
        IdempotencyService.Result<TransferResponse> result
    ) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(
            result.status()
        ).location(URI.create("/api/v1/transfers/" + result.body().id()));
        if (result.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(result.body());
    }

    // ---- API contract ----

    public record CreateTransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @Positive @Max(MAX_AMOUNT) long amount,
        @NotBlank
        @Pattern(
            regexp = "^[A-Z]{3}$",
            message = "must be a 3-letter uppercase ISO 4217 code"
        )
        String currency,
        @Size(max = 140) String reference
    ) {}

    public record DepositRequest(
        @Positive @Max(MAX_AMOUNT) long amount,
        @NotBlank
        @Pattern(
            regexp = "^[A-Z]{3}$",
            message = "must be a 3-letter uppercase ISO 4217 code"
        )
        String currency,
        @Size(max = 140) String reference
    ) {}

    public record TransferResponse(
        UUID id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amount,
        String currency,
        String reference,
        Instant createdAt
    ) {
        static TransferResponse from(Transfer t) {
            return new TransferResponse(
                t.id(),
                t.sourceAccountId(),
                t.destinationAccountId(),
                t.amount(),
                t.currency(),
                t.reference(),
                t.createdAt()
            );
        }
    }
}
