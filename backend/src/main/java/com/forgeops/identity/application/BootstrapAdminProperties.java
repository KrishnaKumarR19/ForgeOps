package com.forgeops.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the bootstrap administrator (SECURITY_DESIGN.md §3).
 *
 * <p>Bound from {@code forgeops.security.bootstrap-admin.*}, which in turn is supplied from
 * the environment (e.g. {@code FORGEOPS_SECURITY_BOOTSTRAP_ADMIN_USERNAME} /
 * {@code FORGEOPS_SECURITY_BOOTSTRAP_ADMIN_PASSWORD}). No values are committed — the
 * password is a secret provided at deploy/dev time and must be rotated after first login.
 *
 * <p>The bootstrap runs only when {@code enabled} is true and both username and password
 * are present; otherwise no credentials are invented.
 */
@ConfigurationProperties(prefix = "forgeops.security.bootstrap-admin")
public class BootstrapAdminProperties {

    /** Whether bootstrap provisioning should run at startup. Default off. */
    private boolean enabled = false;

    /** Bootstrap admin username (from configuration/environment; never committed). */
    private String username;

    /** Bootstrap admin password (secret; from environment; never committed or logged). */
    private String password;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** True only when enabled and both username and password are provided. */
    boolean isFullyConfigured() {
        return enabled
                && username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }
}
