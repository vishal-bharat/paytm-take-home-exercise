package in.me.vishal.seats.exception;

public class ShowNotFoundException extends RuntimeException {
	 

	public ShowNotFoundException(long showId) {
        super("show " + showId + " not found");
    }
}
