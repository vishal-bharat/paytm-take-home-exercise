package in.me.vishal.seats.controller;

import in.me.vishal.seats.dto.ErrorResponse;
import in.me.vishal.seats.exception.*;
import in.me.vishal.seats.observability.ReservationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ReservationMetrics metrics;

    public GlobalExceptionHandler(ReservationMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(DeclineException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse declined(DeclineException e) {
        metrics.declined(e.reason());   // one place counts every decline, whichever path threw it
        return new ErrorResponse(e.reason().code(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalid(IllegalArgumentException e) {
        return new ErrorResponse("invalid_request", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse unreadable(HttpMessageNotReadableException e) {
        return new ErrorResponse("invalid_request", "request body is not valid JSON for this endpoint");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse badPathValue(MethodArgumentTypeMismatchException e) {
        return new ErrorResponse("invalid_request", "invalid value for '" + e.getName() + "'");
    }

    @ExceptionHandler(ShowNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse showNotFound(ShowNotFoundException e) {
        return new ErrorResponse("show_not_found", e.getMessage());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse reservationNotFound(ReservationNotFoundException e) {
        return new ErrorResponse("reservation_not_found", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse spring) {
            HttpStatusCode status = spring.getStatusCode();
            if (status.is4xxClientError()) {
                return ResponseEntity.status(status)
                        .body(new ErrorResponse("invalid_request", spring.getBody().getDetail()));
            }
        }
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal_error", "unexpected error; quote the X-Request-Id header"));
    }
}