package in.me.vishal.seats.controller;

import in.me.vishal.seats.dto.ErrorResponse;
import in.me.vishal.seats.exception.DeclineException;
import in.me.vishal.seats.exception.ReservationNotFoundException;
import in.me.vishal.seats.exception.ShowNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
 
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
    
    @ExceptionHandler(DeclineException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse declined(DeclineException e) {
        return new ErrorResponse(e.reason().code(), null);
    }
    
    @ExceptionHandler(ReservationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse reservationNotFound(ReservationNotFoundException e) {
        return new ErrorResponse("reservation_not_found", e.getMessage());
    }
}
