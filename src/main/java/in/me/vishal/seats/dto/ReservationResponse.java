package in.me.vishal.seats.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;
 
public record ReservationResponse(
        @JsonProperty("reservation_id") UUID reservationId,
        @JsonProperty("show_id") long showId,
        List<String> seats,
        @JsonProperty("amount_paise") long amountPaise,
        String status) {
}
