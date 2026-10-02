package in.me.vishal.seats.controller;

import in.me.vishal.seats.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
 
@RestControllerAdvice
public class GlobalExceptionHandler {
 
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
}
