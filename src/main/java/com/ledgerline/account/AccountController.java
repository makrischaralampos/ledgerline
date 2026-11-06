package com.ledgerline.account;

import com.ledgerline.ledger.Direction;
import com.ledgerline.ledger.LedgerEntry;
import com.ledgerline.ledger.LedgerEntryPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(
        @Valid @RequestBody CreateAccountRequest request
    ) {
        Account account = service.create(request.name(), request.currency());
        return ResponseEntity.created(
            URI.create("/api/v1/accounts/" + account.id())
        ).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(service.get(id));
    }

    @GetMapping("/{id}/entries")
    public EntryPageResponse entries(
        @PathVariable UUID id,
        @RequestParam(required = false) Long cursor,
        @RequestParam(defaultValue = "50") int limit
    ) {
        return EntryPageResponse.from(service.entries(id, cursor, limit));
    }

    // ---- API contract ----

    public record CreateAccountRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank
        @Pattern(
            regexp = "^[A-Z]{3}$",
            message = "must be a 3-letter uppercase ISO 4217 code"
        )
        String currency
    ) {}

    public record AccountResponse(
        UUID id,
        String name,
        String currency,
        AccountType type,
        long balance,
        Instant createdAt
    ) {
        static AccountResponse from(Account a) {
            return new AccountResponse(
                a.id(),
                a.name(),
                a.currency(),
                a.type(),
                a.balance(),
                a.createdAt()
            );
        }
    }

    public record EntryResponse(
        long id,
        UUID transferId,
        Direction direction,
        long amount,
        String currency,
        long balanceAfter,
        Instant createdAt
    ) {
        static EntryResponse from(LedgerEntry e) {
            return new EntryResponse(
                e.id(),
                e.transferId(),
                e.direction(),
                e.amount(),
                e.currency(),
                e.balanceAfter(),
                e.createdAt()
            );
        }
    }

    public record EntryPageResponse(
        List<EntryResponse> items,
        Long nextCursor
    ) {
        static EntryPageResponse from(LedgerEntryPage page) {
            return new EntryPageResponse(
                page.items().stream().map(EntryResponse::from).toList(),
                page.nextCursor()
            );
        }
    }
}
