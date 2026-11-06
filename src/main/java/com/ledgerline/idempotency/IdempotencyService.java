package com.ledgerline.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes an operation exactly-once per Idempotency-Key.
 *
 * <p>The key claim, the operation, and the stored response all happen in ONE transaction, so they
 * commit or roll back together. Concurrent requests with the same key are serialized by the unique
 * index: the loser blocks until the winner commits, then replays the stored response.
 */
@Service
public class IdempotencyService {

    private static final Pattern VALID_KEY = Pattern.compile(
        "^[\\x21-\\x7E]{1,255}$"
    );

    public record Result<T>(int status, T body, boolean replayed) {}

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(
        IdempotencyKeyRepository repository,
        ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> Result<T> execute(
        String key,
        String fingerprint,
        Class<T> responseType,
        HttpStatus successStatus,
        Supplier<T> action
    ) {
        validate(key);

        if (!repository.tryClaim(key, fingerprint)) {
            return replay(key, fingerprint, responseType);
        }

        // Exceptions thrown here roll back the transaction, including the key claim above.
        T response = action.get();
        repository.complete(key, successStatus.value(), serialize(response));
        return new Result<>(successStatus.value(), response, false);
    }

    @Transactional
    public int purgeExpired(Duration retention) {
        return repository.deleteCreatedBefore(
            OffsetDateTime.now(ZoneOffset.UTC).minus(retention)
        );
    }

    /** Stable SHA-256 over the operation name and its parameters. */
    public static String fingerprint(Object... parts) {
        String canonical = Arrays.stream(parts)
            .map(p -> p == null ? "" : p.toString())
            .collect(Collectors.joining("\u001f"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                canonical.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> Result<T> replay(
        String key,
        String fingerprint,
        Class<T> responseType
    ) {
        IdempotencyKeyRepository.StoredKey stored = repository
            .find(key)
            .orElseThrow(() ->
                new IllegalStateException(
                    "Idempotency key vanished after conflict: " + key
                )
            );

        if (!stored.requestHash().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(key);
        }
        if (!"COMPLETED".equals(stored.status())) {
            // Unreachable: uncommitted rows are never visible, and we block on them in tryClaim.
            throw new IllegalStateException(
                "Idempotency key in unexpected state: " + stored.status()
            );
        }
        return new Result<>(
            stored.responseStatus(),
            deserialize(stored.responseBody(), responseType),
            true
        );
    }

    private static void validate(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidIdempotencyKeyException(
                "IDEMPOTENCY_KEY_REQUIRED",
                "The Idempotency-Key header is required"
            );
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException(
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key must be 1-255 printable ASCII characters without spaces (a UUID is recommended)"
            );
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Cannot serialize response for idempotency storage",
                e
            );
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Cannot deserialize stored idempotent response",
                e
            );
        }
    }
}
