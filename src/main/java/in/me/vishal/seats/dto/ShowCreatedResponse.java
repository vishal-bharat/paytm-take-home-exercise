package in.me.vishal.seats.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ShowCreatedResponse(
        long id,
        String name,
        @JsonProperty("price_paise") long pricePaise,
        @JsonProperty("per_user_limit") int perUserLimit,
        @JsonProperty("total_seats") int totalSeats) {
}
