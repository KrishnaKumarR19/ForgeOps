package com.forgeops.incidents.infrastructure;

import com.forgeops.incidents.domain.UserExistenceReader;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the shared {@code users} table to check assignee existence for the incidents module
 * (implements {@link UserExistenceReader}, ADR-0030). Plain {@link JdbcTemplate}; does not touch
 * the identity module's internals, keeping the module boundary intact. Read-only.
 */
@Repository
class JdbcUserExistenceReader implements UserExistenceReader {

    private final JdbcTemplate jdbcTemplate;

    JdbcUserExistenceReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean exists(UUID userId) {
        if (userId == null) {
            return false;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Long.class, userId);
        return count != null && count > 0;
    }
}
