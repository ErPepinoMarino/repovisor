package com.throttlelab.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness mínima. En fases posteriores se sustituye por
 * spring-boot-starter-actuator (/actuator/health), que además de decir "vivo"
 * dirá si Postgres y Redis están disponibles.
 */
@RestController
public class HealthController {

	@GetMapping("/healthz")
	Map<String, String> health() {
		return Map.of("status", "ok");
	}
}