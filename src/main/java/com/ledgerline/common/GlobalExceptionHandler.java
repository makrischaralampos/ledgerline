package com.ledgerline.common;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Translates every failure into an RFC 9457 application/problem+json response. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(
        GlobalExceptionHandler.class
    );

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomain(DomainException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            ex.status(),
            ex.getMessage()
        );
        problem.setTitle(ex.status().getReasonPhrase());
        problem.setType(problemType(ex.code()));
        problem.setProperty("code", ex.code());
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Request validation failed"
        );
        problem.setTitle("Validation failed");
        problem.setType(problemType("VALIDATION_FAILED"));
        problem.setProperty("code", "VALIDATION_FAILED");
        List<Map<String, String>> errors = ex
            .getBindingResult()
            .getFieldErrors()
            .stream()
            .map(fe ->
                Map.of(
                    "field",
                    fe.getField(),
                    "message",
                    String.valueOf(fe.getDefaultMessage())
                )
            )
            .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(
            ex,
            problem,
            headers,
            HttpStatus.BAD_REQUEST,
            request
        );
    }

    /** Last resort: never leak internals to the client. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred"
        );
        problem.setTitle("Internal Server Error");
        problem.setType(problemType("INTERNAL_ERROR"));
        problem.setProperty("code", "INTERNAL_ERROR");
        return problem;
    }

    private static URI problemType(String code) {
        return URI.create(
            "urn:ledgerline:problem:" + code.toLowerCase().replace('_', '-')
        );
    }
}
