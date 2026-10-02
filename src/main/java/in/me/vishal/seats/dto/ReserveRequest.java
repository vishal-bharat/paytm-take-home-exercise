package in.me.vishal.seats.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
 
public record ReserveRequest(
        List<String> seats,
        @JsonProperty("idempotency_key") String idempotencyKey) {
}
