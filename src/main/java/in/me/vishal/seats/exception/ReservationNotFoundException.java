package in.me.vishal.seats.exception;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {
 
    public ReservationNotFoundException(UUID id) {
        super("reservation " + id + " not found");
    }
}
