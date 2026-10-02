package in.me.vishal.seats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SeatsApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeatsApplication.class, args);
	}

}
