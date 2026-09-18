package com.repovisor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class RepovisorApplication {

	public static void main(String[] args) {
		SpringApplication.run(RepovisorApplication.class, args);
	}

}
@RestController
class HealthController {

    @GetMapping("/healthz")
    String health() {
        return "{\"status\": \"ok\"}";
    }
}