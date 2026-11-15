package in.codefarm.stress_testing_microservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StressTestingMicroserviceApplication {

	public static void main(String[] args) {
		SpringApplication.run(StressTestingMicroserviceApplication.class, args);
	}

}
