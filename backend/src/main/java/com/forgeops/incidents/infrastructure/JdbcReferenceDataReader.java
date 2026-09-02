package com.forgeops.incidents.infrastructure;

import com.forgeops.incidents.domain.ReferenceDataReader;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the shared {@code services}/{@code environments} reference tables to resolve keys ↔ ids
 * for the incidents module (implements {@link ReferenceDataReader}, ADR-0030). Uses a plain
 * {@link JdbcTemplate} against the controlled reference data — it does not touch the events
 * module's internals, keeping the module boundary intact. Read-only.
 */
@Repository
class JdbcReferenceDataReader implements ReferenceDataReader {

    private final JdbcTemplate jdbcTemplate;

    JdbcReferenceDataReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UUID> findServiceIdByKey(String serviceKey) {
        return queryForUuid("SELECT id FROM services WHERE key = ?", serviceKey);
    }

    @Override
    public Optional<UUID> findEnvironmentIdByKey(String environmentKey) {
        return queryForUuid("SELECT id FROM environments WHERE key = ?", environmentKey);
    }

    @Override
    public Optional<String> findServiceKeyById(UUID serviceId) {
        return queryForString("SELECT key FROM services WHERE id = ?", serviceId);
    }

    @Override
    public Optional<String> findEnvironmentKeyById(UUID environmentId) {
        return queryForString("SELECT key FROM environments WHERE id = ?", environmentId);
    }

    private Optional<UUID> queryForUuid(String sql, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(sql, UUID.class, key));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Optional<String> queryForString(String sql, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(sql, String.class, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
