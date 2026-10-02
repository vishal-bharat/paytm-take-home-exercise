package in.me.vishal.seats.dto;

public enum DeclineReason {
    SEAT_TAKEN("seat_taken"),
    PER_USER_LIMIT("per_user_limit"),
    IDEMPOTENCY_CONFLICT("idempotency_conflict");
 
    private final String code;
 
    DeclineReason(String code) {
        this.code = code;
    }
 
    public String code() {
        return code;
    }
}