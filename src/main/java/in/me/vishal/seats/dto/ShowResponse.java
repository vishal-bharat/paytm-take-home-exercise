package in.me.vishal.seats.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
 
public record ShowResponse(
        long id,
        String name,
        @JsonProperty("price_paise") long pricePaise,
        @JsonProperty("per_user_limit") int perUserLimit,
        @JsonProperty("total_seats") int totalSeats,
        Counts counts,
        List<Seat> seats) {
 
    public record Counts(int available, int held, int confirmed) {
    }
 
    public record Seat(String label, String status) {
    }
}
