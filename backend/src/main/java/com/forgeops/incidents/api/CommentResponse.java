package com.forgeops.incidents.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Comment representation (API_CONTRACTS.md §13/§26): id, author, category, body, created_at.
 * Snake_case matches the contract. {@code author} is the author's user id.
 *
 * @param id        comment id
 * @param author    author user id
 * @param category  optional category (null if uncategorized)
 * @param body      comment content
 * @param createdAt authorship time
 */
public record CommentResponse(
        String id,
        String author,
        String category,
        String body,
        @JsonProperty("created_at") Instant createdAt) {
}
