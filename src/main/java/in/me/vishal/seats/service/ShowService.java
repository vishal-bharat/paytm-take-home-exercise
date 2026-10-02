package in.me.vishal.seats.service;

import in.me.vishal.seats.dto.CreateShowRequest;
import in.me.vishal.seats.dto.ShowCreatedResponse;
import in.me.vishal.seats.dto.ShowResponse;
import in.me.vishal.seats.exception.ShowNotFoundException;
import in.me.vishal.seats.repo.SeatRepository;
import in.me.vishal.seats.repo.SeatRepository.SeatRow;
import in.me.vishal.seats.repo.ShowRepository;
import in.me.vishal.seats.repo.ShowRepository.ShowRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
 
import java.util.HashSet;
import java.util.List;
 
@Service
public class ShowService {
 
    private static final int DEFAULT_PER_USER_LIMIT = 4;
    private static final int MAX_SEATS = 100_000;
    private static final int MAX_LABEL_LENGTH = 16;
 
    private final ShowRepository shows;
    private final SeatRepository seats;
 
    public ShowService(ShowRepository shows, SeatRepository seats) {
        this.shows = shows;
        this.seats = seats;
    }
 
    @Transactional
    public ShowCreatedResponse create(CreateShowRequest req) {
        String name = (req.name() == null || req.name().isBlank()) ? "show" : req.name().trim();
        long price = requirePrice(req.pricePaise());
        int limit = req.perUserLimit() == null ? DEFAULT_PER_USER_LIMIT : req.perUserLimit();
        if (limit < 1) {
            throw new IllegalArgumentException("per_user_limit must be at least 1");
        }
        List<String> labels = requireLabels(req.seats());
 
        long id = shows.insert(name, price, limit, labels.size());
        seats.insertAll(id, labels);
        return new ShowCreatedResponse(id, name, price, limit, labels.size());
    }
 
    
    @Transactional(readOnly = true)
    public ShowResponse get(long showId) {
        ShowRow show = shows.find(showId).orElseThrow(() -> new ShowNotFoundException(showId));
        List<SeatRow> rows = seats.findByShow(showId);
 
        int available = 0;
        int confirmed = 0;
        for (SeatRow row : rows) {
            switch (row.status()) {
                case "available" -> available++;
                case "confirmed" -> confirmed++;
                default -> throw new IllegalStateException("unknown seat status: " + row.status());
            }
        }
        int held = 0; // explicit-cancel model: reserve goes straight to confirmed, nothing is ever held
 
        List<ShowResponse.Seat> seatViews = rows.stream()
                .map(r -> new ShowResponse.Seat(r.label(), r.status()))
                .toList();
 
        return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                show.totalSeats(), new ShowResponse.Counts(available, held, confirmed), seatViews);
    }
 
    private static long requirePrice(Long pricePaise) {
        if (pricePaise == null || pricePaise < 0) {
            throw new IllegalArgumentException("price_paise must be a non-negative integer");
        }
        return pricePaise;
    }
 
    private static List<String> requireLabels(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("seats must be a non-empty list");
        }
        if (raw.size() > MAX_SEATS) {
            throw new IllegalArgumentException("at most " + MAX_SEATS + " seats per show");
        }
        List<String> labels = raw.stream()
                .map(s -> s == null ? "" : s.trim())
                .toList();
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                throw new IllegalArgumentException("each seat label must be 1-" + MAX_LABEL_LENGTH + " characters");
            }
        }
        if (new HashSet<>(labels).size() != labels.size()) {
            throw new IllegalArgumentException("seat labels must be unique");
        }
        return labels;
    }
}