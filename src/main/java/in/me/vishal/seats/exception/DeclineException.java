package in.me.vishal.seats.exception;

import in.me.vishal.seats.dto.DeclineReason;

public class DeclineException extends RuntimeException {
 
    private final DeclineReason reason;
 
    public DeclineException(DeclineReason reason) {
        super(reason.code(), null, false, false); 
        this.reason = reason;
    }
 
    public DeclineReason reason() {
        return reason;
    }
}
