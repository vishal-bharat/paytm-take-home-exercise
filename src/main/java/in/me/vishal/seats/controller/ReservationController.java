package in.me.vishal.seats.controller;

import in.me.vishal.seats.dto.ReservationResponse;
import in.me.vishal.seats.dto.ReserveRequest;
import in.me.vishal.seats.service.ReservationService;
import in.me.vishal.seats.service.ReservationService.Result;
import in.me.vishal.seats.observability.ReservationMetrics;
import in.me.vishal.seats.security.JwtAuthFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ReservationController {

    private final ReservationService reservations;
    private final ReservationMetrics metrics;

    public ReservationController(ReservationService reservations, ReservationMetrics metrics) {
        this.reservations = reservations;
        this.metrics = metrics;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable long id,
            @RequestAttribute(JwtAuthFilter.USER_ID) String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @RequestBody ReserveRequest req) {

        String bodyKey = req.idempotencyKey();
        if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
            throw new IllegalArgumentException("Idempotency-Key header and idempotency_key field differ");
        }
        String key = headerKey != null ? headerKey : bodyKey;

        // counted only after the transaction committed, so a retried transaction is never counted twice
        Result result = reservations.reserve(id, userId, key, req.seats());
        if (result.replay()) {
            metrics.replayed();
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.reservation());
        }
        metrics.confirmed();
        return ResponseEntity.status(HttpStatus.CREATED).body(result.reservation());
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(
            @PathVariable UUID id,
            @RequestAttribute(JwtAuthFilter.USER_ID) String userId) {
        Result result = reservations.cancel(id, userId);
        if (!result.replay()) {
            metrics.cancelled();
        }
        return result.reservation();
    }
}