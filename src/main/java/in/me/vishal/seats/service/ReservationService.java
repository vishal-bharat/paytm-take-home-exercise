package in.me.vishal.seats.service;

import in.me.vishal.seats.dto.DeclineReason;
import in.me.vishal.seats.dto.ReservationResponse;
import in.me.vishal.seats.exception.DeclineException;
import in.me.vishal.seats.exception.ReservationNotFoundException;
import in.me.vishal.seats.exception.ShowNotFoundException;
import in.me.vishal.seats.repo.QuotaRepository;
import in.me.vishal.seats.repo.ReservationRepository;
import in.me.vishal.seats.repo.ReservationRepository.ReservationRow;
import in.me.vishal.seats.repo.SeatRepository;
import in.me.vishal.seats.repo.SeatRepository.SeatRow;
import in.me.vishal.seats.repo.ShowRepository;
import in.me.vishal.seats.repo.ShowRepository.ShowRow;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReservationService {

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_KEY_LENGTH = 128;

    public record Result(ReservationResponse reservation, boolean replay) {
    }

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final QuotaRepository quotas;
    private final TransactionTemplate tx;

    public ReservationService(ShowRepository shows, SeatRepository seats, ReservationRepository reservations,
                              QuotaRepository quotas, TransactionTemplate tx) {
        this.shows = shows;
        this.seats = seats;
        this.reservations = reservations;
        this.quotas = quotas;
        this.tx = tx;
    }


    public Result reserve(long showId, String userId, String idempotencyKey, List<String> requestedSeats) {
        List<String> labels = normalize(requestedSeats);
        String key = normalizeKey(idempotencyKey);
        ShowRow show = shows.find(showId).orElseThrow(() -> new ShowNotFoundException(showId));
        String hash = requestHash(showId, labels);

        Optional<ReservationRow> existing = reservations.findByKey(userId, key);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), hash);
        }

        if (seats.anyConfirmed(showId, labels)) {
            existing = reservations.findByKey(userId, key);
            if (existing.isPresent()) {
                return replayOrConflict(existing.get(), hash);
            }
            throw new DeclineException(DeclineReason.SEAT_TAKEN);
        }

        long amount = Math.multiplyExact(show.pricePaise(), labels.size());
        return inTransaction(status -> decide(show, userId, key, hash, labels, amount));
    }

    private Result decide(ShowRow show, String userId, String key, String hash, List<String> labels, long amount) {
        Optional<UUID> inserted = reservations.insertPending(show.id(), userId, labels, amount, key, hash);
        if (inserted.isEmpty()) {
            // A concurrent request with the same key committed first; we waited for it, so it's visible now.
            ReservationRow row = reservations.findByKey(userId, key)
                    .orElseThrow(() -> new IllegalStateException("idempotency key vanished"));
            return replayOrConflict(row, hash);
        }
        UUID reservationId = inserted.get();

        if (!quotas.tryConsume(show.id(), userId, labels.size(), show.perUserLimit())) {
            throw new DeclineException(DeclineReason.PER_USER_LIMIT);
        }

        List<SeatRow> locked = seats.lockForUpdate(show.id(), labels);
        if (locked.size() != labels.size()) {
            throw new IllegalArgumentException("unknown seat label for this show");
        }
        if (locked.stream().anyMatch(s -> !"available".equals(s.status()))) {
            throw new DeclineException(DeclineReason.SEAT_TAKEN); // all-or-nothing
        }
        int updated = seats.confirm(show.id(), labels, userId, reservationId);
        if (updated != labels.size()) {
            throw new IllegalStateException("locked seats changed underneath us"); // unreachable while locked
        }
        reservations.markConfirmed(reservationId);

        return new Result(new ReservationResponse(reservationId, show.id(), labels, amount, "confirmed"), false);
    }

    public ReservationResponse cancel(UUID reservationId, String userId) {
        return inTransaction(status -> {
            ReservationRow row = reservations.lockById(reservationId)
                    .filter(r -> r.userId().equals(userId))   // someone else's = not found
                    .orElseThrow(() -> new ReservationNotFoundException(reservationId));

            if ("cancelled".equals(row.status())) {
                return toResponse(row, "cancelled");
            }

            if (!quotas.release(row.showId(), userId, row.seats().size())) {
                throw new IllegalStateException("quota row missing for reservation " + reservationId);
            }

            List<String> labels = row.seats().stream().sorted().toList();
            seats.lockForUpdate(row.showId(), labels);
            int freed = seats.release(row.showId(), reservationId);
            if (freed != labels.size()) {
                // a confirmed reservation must own exactly its seats; anything else is a broken invariant
                throw new IllegalStateException("reservation " + reservationId + " owned " + freed
                        + " seats, expected " + labels.size());
            }
            reservations.markCancelled(reservationId);
            return toResponse(row, "cancelled");
        });
    }

    private <T> T inTransaction(TransactionCallback<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(work);
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private static Result replayOrConflict(ReservationRow row, String hash) {
        if (!row.requestHash().equals(hash)) {
            throw new DeclineException(DeclineReason.IDEMPOTENCY_CONFLICT);
        }
        return new Result(toResponse(row, row.status()), true);
    }

    private static ReservationResponse toResponse(ReservationRow row, String status) {
        return new ReservationResponse(row.id(), row.showId(), row.seats(), row.amountPaise(), status);
    }

    private static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("seats must be a non-empty list");
        }
        List<String> labels = raw.stream().map(s -> s == null ? "" : s.trim()).sorted().toList();
        if (labels.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("seat labels must not be empty");
        }
        if (new HashSet<>(labels).size() != labels.size()) {
            throw new IllegalArgumentException("seat labels must be unique");
        }
        return labels;
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return "auto-" + UUID.randomUUID();
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotency key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String requestHash(long showId, List<String> sortedLabels) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((showId + "\n" + String.join("\n", sortedLabels)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); 
        }
    }
}