package com.wallet.adapter.in.web;

import com.wallet.infrastructure.exception.FxRateUnavailableException;
import com.wallet.infrastructure.exception.IdempotencyKeyConflictException;
import com.wallet.infrastructure.exception.WalletApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException validationException) {
        String validationMessage = validationException.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
            .orElse("Validation failed");
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR", validationMessage));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadableBody(HttpMessageNotReadableException unreadableBodyException) {
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR", "Malformed or unreadable request body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> handlePathTypeMismatch(MethodArgumentTypeMismatchException typeMismatchException) {
        String parameterName = typeMismatchException.getName();
        String validationMessage = "Invalid value for path parameter '" + parameterName + "'";
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR", validationMessage));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleIllegalArgument(IllegalArgumentException illegalArgumentException) {
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR", illegalArgumentException.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorBody> handleIdempotencyConflict(IdempotencyKeyConflictException idempotencyConflictException) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorBody("IDEMPOTENCY_KEY_CONFLICT", idempotencyConflictException.getMessage()));
    }

    @ExceptionHandler(FxRateUnavailableException.class)
    public ResponseEntity<ErrorBody> handleFxRateUnavailable(FxRateUnavailableException fxRateUnavailableException) {
        log.warn("FX rate provider unavailable", fxRateUnavailableException);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorBody("FX_RATE_UNAVAILABLE", "FX rate service is temporarily unavailable"));
    }

    @ExceptionHandler(WalletApiException.class)
    public ResponseEntity<ErrorBody> handleWalletApi(WalletApiException walletApiException) {
        return ResponseEntity.status(walletApiException.getStatus()).body(new ErrorBody(walletApiException.getCode(), walletApiException.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> handleNoResourceFound(NoResourceFoundException noResourceFoundException) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorBody("NOT_FOUND", "No endpoint at " + noResourceFoundException.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpectedException(Exception unexpectedException) {
        log.error("Unexpected error", unexpectedException);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorBody("INTERNAL_ERROR", "Unexpected error"));
    }

    public record ErrorBody(String error, String message) {}
}
