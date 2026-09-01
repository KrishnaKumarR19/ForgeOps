package com.forgeops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ForgeOps backend entry point.
 *
 * <p>Phase 2 repository foundation: this application starts, exposes a health endpoint
 * (via Spring Boot Actuator), and shuts down cleanly. It intentionally contains no
 * business behavior. Domain modules live under {@code com.forgeops.<module>} and are
 * empty in this phase.
 *
 * <p>The component scan is rooted at {@code com.forgeops}, so each domain module package
 * is part of the single deployable modular monolith described in ARCHITECTURE.md.
 */
@SpringBootApplication
public class ForgeOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgeOpsApplication.class, args);
    }
}
