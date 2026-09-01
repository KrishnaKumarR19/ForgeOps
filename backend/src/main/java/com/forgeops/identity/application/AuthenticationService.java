package com.forgeops.identity.application;

import com.forgeops.identity.domain.User;
import com.forgeops.identity.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a validated access token into an {@link AuthenticatedUser}, enforcing the
 * account-status rule that keeps PostgreSQL authoritative (SECURITY_DESIGN.md §12, §7).
 *
 * <p>Per §12 the roles come from the validated token claim (not re-queried), because token
 * lifetime is short and the source of truth for role changes is the next login. However,
 * immediate lockout of a user is achieved by <strong>deactivating</strong> them, which is
 * checked here on every request: the {@code sub} is resolved against the repository and the
 * request is rejected if the user is unknown or not {@code ACTIVE}. An old token therefore
 * cannot bypass account deactivation.
 *
 * <p>This service does not mutate the user and does not change roles during authentication.
 */
@Service
public class AuthenticationService {

    private final UserRepository users;

    public AuthenticationService(UserRepository users) {
        this.users = users;
    }

    /**
     * Resolves the validated token to an active persisted user.
     *
     * @param token the already-validated token claims
     * @return the authenticated principal (roles taken from the token per §12)
     * @throws InvalidAccessTokenException if the subject is unknown or the account is not
     *                                     active (generic message; no account enumeration)
     */
    @Transactional(readOnly = true)
    public AuthenticatedUser authenticate(ValidatedAccessToken token) {
        User user = users.findById(token.userId())
                .orElseThrow(() -> new InvalidAccessTokenException("Authentication failed"));
        if (!user.isActive()) {
            // Deactivated (or otherwise non-active) accounts cannot authenticate, even with
            // an otherwise-valid, unexpired token. Message stays generic.
            throw new InvalidAccessTokenException("Authentication failed");
        }
        // Roles come from the token claim (SECURITY_DESIGN.md §12), not re-queried here.
        return new AuthenticatedUser(user.id(), token.roles());
    }
}
