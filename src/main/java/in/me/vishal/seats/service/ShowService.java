package in.me.vishal.seats.service;

import in.me.vishal.seats.dto.CreateShowRequest;
import in.me.vishal.seats.dto.ShowCreatedResponse;
import in.me.vishal.seats.repo.SeatRepository;
import in.me.vishal.seats.repo.ShowRepository;
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
 
    /** Show row and seat rows commit together, or not at all. */
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