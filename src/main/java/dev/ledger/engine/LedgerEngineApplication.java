package dev.ledger.engine;

import dev.ledger.engine.config.LedgerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerEngineApplication.class, args);
    }
}
