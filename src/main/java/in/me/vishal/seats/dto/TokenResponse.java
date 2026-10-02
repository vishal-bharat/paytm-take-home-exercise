package in.me.vishal.seats.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenResponse(String token, @JsonProperty("expires_in") long expiresIn) {
}