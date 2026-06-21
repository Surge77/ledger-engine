package dev.ledger.engine.controller;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness/readiness check at the plan's /health path. Pings the DB so a green
 * 200 means the service can actually serve ledger reads, not just that the JVM is up.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Integer ok = jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "UP", "db", ok != null && ok == 1 ? "UP" : "DOWN");
    }
}
