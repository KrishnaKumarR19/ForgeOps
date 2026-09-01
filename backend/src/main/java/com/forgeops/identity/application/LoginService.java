package com.forgeops.identity.application;

import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.PasswordHasher;
import com.forgeops.identity.domain.User;
import com.forgeops.identity.domain.UserRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login use case (Phase 4.2 Slice 3, SECURITY_DESIGN.md §6).
 *
 * <p>Loads the user from the authoritative PostgreSQL-backed repository, rejects unknown,
 * disabled, and wrong-password logins with the <strong>same</strong> generic
 * {@link InvalidCredentialsException} (no user enumeration), and — only on success — issues
 * a short-lived RS256 access token via the {@link AccessTokenIssuer} port.
 *
 * <p>Password verification always goes through {@link PasswordHasher}; there is no manual
 * comparison. When the user is absent, verification runs against a dummy hash so the
 * response time is comparable to the real path (mitigating a timing oracle). The user is
 * never modified during login. Passwords and hashes are never logged.
 */
@Service
public class LoginService {

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer accessTokenIssuer;

    /**
     * A dummy hash used to keep timing comparable when the username is unknown. Computed
     * once at startup from a random throwaway value; it can never match a real password.
     */
    private final PasswordHash dummyHash;

    public LoginService(UserRepository users,
                        PasswordHasher passwordHasher,
                        AccessTokenIssuer accessTokenIssuer) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.accessTokenIssuer = accessTokenIssuer;
        this.dummyHash = passwordHasher.hash("dummy-" + java.util.UUID.randomUUID());
    }

    /**
     * Authenticates the given credentials and, on success, returns a freshly issued access
     * token.
     *
     * @throws InvalidCredentialsException for unknown user, disabled user, or wrong password
     *                                     (indistinguishable to the caller)
     */
    @Transactional(readOnly = true)
    public IssuedAccessToken login(String username, CharSequence password) {
        if (username == null || username.isBlank() || password == null || password.length() == 0) {
            throw new InvalidCredentialsException();
        }

        Optional<User> maybeUser = users.findByUsername(username);

        // Always run a verification to keep timing comparable, even for unknown users.
        if (maybeUser.isEmpty()) {
            passwordHasher.verify(password, dummyHash);
            throw new InvalidCredentialsException();
        }

        User user = maybeUser.get();
        boolean passwordMatches =
                user.hasCredential() && passwordHasher.verify(password, user.passwordHash());

        // Disabled users and wrong passwords fail identically to the unknown-user case.
        if (!user.isActive() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }

        return accessTokenIssuer.issueFor(user);
    }
}
