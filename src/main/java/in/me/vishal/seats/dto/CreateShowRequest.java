package in.me.vishal.seats.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
 
public record CreateShowRequest(
        String name,
        @JsonProperty("price_paise") Long pricePaise,
        @JsonProperty("per_user_limit") Integer perUserLimit,
        List<String> seats) {
}
