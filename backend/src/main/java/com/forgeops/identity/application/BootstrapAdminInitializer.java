package com.forgeops.identity.application;

import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.UserRepository;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the bootstrap administrator at startup, idempotently (SECURITY_DESIGN.md §3).
 *
 * <p>Behavior:
 * <ul>
 *   <li>If bootstrap is not fully configured (disabled, or missing username/password),
 *       do nothing — no credentials are invented.</li>
 *   <li>If a user with the configured bootstrap username already exists, do nothing:
 *       the existing account is left completely unchanged (no password replacement, no
 *       role reset). This makes repeated startups safe and never overwrites an existing
 *       account — the design's idempotent, non-overwriting rule.</li>
 *   <li>Otherwise, provision an ADMIN through the normal
 *       {@link UserProvisioningService} path (server-generated ID, Argon2id hash).</li>
 * </ul>
 *
 * <p>The bootstrap password is a secret: it is never logged, and only the username and the
 * outcome (created / already present) are logged.
 */
@Configuration
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final BootstrapAdminProperties properties;
    private final UserProvisioningService provisioning;
    private final UserRepository users;

    public BootstrapAdminInitializer(BootstrapAdminProperties properties,
                                     UserProvisioningService provisioning,
                                     UserRepository users) {
        this.properties = properties;
        this.provisioning = provisioning;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isFullyConfigured()) {
            log.info("Bootstrap administrator not configured; skipping bootstrap provisioning.");
            return;
        }

        String username = properties.getUsername();
        if (users.existsByUsername(username)) {
            // Idempotent: leave the existing account untouched (no overwrite, no role reset).
            log.info("Bootstrap administrator '{}' already exists; leaving it unchanged.", username);
            return;
        }

        provisioning.provision(username, properties.getPassword(), EnumSet.of(Role.ADMIN));
        log.info("Bootstrap administrator '{}' provisioned.", username);
    }
}
